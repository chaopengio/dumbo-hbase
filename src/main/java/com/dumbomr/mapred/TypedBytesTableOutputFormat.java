/**
 * Copyright 2009 Last.fm
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.dumbomr.mapred;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapred.FileAlreadyExistsException;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.InvalidJobConfException;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.record.Buffer;
import org.apache.hadoop.typedbytes.TypedBytesWritable;
import org.apache.hadoop.util.Progressable;

/**
 * This is a modified version of
 * org.apache.hadoop.hbase.mapred.TableOutputFormat. Converts Map/Reduce
 * typedbytes output and write it to an HBase table
 */

public class TypedBytesTableOutputFormat extends
		FileOutputFormat<TypedBytesWritable, TypedBytesWritable> {

	/** JobConf parameter that specifies the output table */
	public static final String OUTPUT_TABLE = "hbase.mapred.outputtable";
	private static final Log LOG = LogFactory
			.getLog(TypedBytesTableOutputFormat.class);
	public static final String ZK_HOST = "hbase.zookeeper.quorum";

	/**
	 * Convert Reduce output (key, value) to (HStoreKey, KeyedDataArrayWritable)
	 * and write to an HBase table
	 */
	protected static class TableRecordWriter implements
			RecordWriter<TypedBytesWritable, TypedBytesWritable> {
		private HTable m_table;

		/**
		 * Instantiate a TableRecordWriter with the HBase HClient for writing.
		 * 
		 * @param table
		 */
		public TableRecordWriter(HTable table) {
			m_table = table;
		}

		public void close(Reporter reporter) throws IOException {
			m_table.close();
		}

		@SuppressWarnings("unchecked")
		public void write(TypedBytesWritable key, TypedBytesWritable value)
				throws IOException {
			Put put = null;

			if (key == null || value == null) {
				return;
			}
			try {
				Object keyObj = key.getValue();
				
				put = new Put(getBytesFromObj(keyObj));
			} catch (Exception e) {
				throw new IOException("expecting key of type byte[]", e);
			}

			try {
//				LOG.info("pc-----value.getValue()");
				Map columns = (Map) value.getValue();
//				LOG.info("pc-----value.getValue(): " + columns.toString());
//				LOG.info("pc-----columns.keySet(): " + columns.keySet());

				for (Object famObj : columns.keySet()) {
//					LOG.info("pc-----begin get family: " + famObj.toString());
//					LOG.info("pc-----type(famObj)"
//							+ famObj.getClass().getName());
					byte[] family = getBytesFromObj(famObj);
//					LOG.info("pc-----get family: " + new String(family));

					Object qualifierCellValueObj = columns.get(famObj);
					if (qualifierCellValueObj == null) {
//						LOG.info("pc-----get qualifier: null");
						continue;
					}
//					LOG.info("pc-----get qualifier: "
//							+ qualifierCellValueObj.toString());

					// TODO: the cell type could be not String
					Map qualifierCellValue = (Map) qualifierCellValueObj;
//					LOG.info("pc-----change format for qualifierCellValue");
					for (Object qualifierObj : qualifierCellValue.keySet()) {
						byte[] qualifier = getBytesFromObj(qualifierObj);
						Object cellValueObj = qualifierCellValue
								.get(qualifierObj);
						if (cellValueObj == null) {
							continue;
						}
//						LOG.info("pc-----get cell: " + cellValueObj.toString());
						byte[] cellValue = getBytesFromObj(cellValueObj);
						put.add(family, qualifier, cellValue);
					}
				}
			} catch (Exception e) {
				throw new IOException(
						"couldn't get column values, expecting Map<Buffer, Map<Buffer, Buffer>>",
						e);
			}
			m_table.put(put);
		}

		private byte[] getBytesFromObj(Object obj) {
			if (obj instanceof Boolean) {
				return Bytes.toBytes(((Boolean) obj).booleanValue());
			} else if (obj instanceof Integer) {
				return Bytes.toBytes(((Integer) obj).intValue());
			} else if (obj instanceof Long) {
				return Bytes.toBytes(((Long) obj).longValue());
			} else if (obj instanceof Float) {
				return Bytes.toBytes(((Float) obj).floatValue());
			} else if (obj instanceof Double) {
				return Bytes.toBytes(((Double) obj).doubleValue());
			} else if (obj instanceof String) {
				return Bytes.toBytes(obj.toString());
			} else if (obj instanceof Buffer) {
				return ((Buffer) obj).get();
			} else {
				throw new RuntimeException("unknown type");
			}
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public RecordWriter getRecordWriter(FileSystem ignored, JobConf job,
			String name, Progressable progress) throws IOException {

		// expecting exactly one path

		String tableName = job.get(OUTPUT_TABLE);
		String zk_config = job.get(ZK_HOST);
		HTable table = null;
		Configuration config = HBaseConfiguration.create(job);
		if (zk_config != null) {
			config.set(ZK_HOST, zk_config);
		}

		try {
			table = new HTable(config, tableName);
		} catch (IOException e) {
			LOG.error(e);
			throw e;
		}
		table.setAutoFlush(false, true);
		return new TableRecordWriter(table);
	}

	@Override
	public void checkOutputSpecs(FileSystem ignored, JobConf job)
			throws FileAlreadyExistsException, InvalidJobConfException,
			IOException {

		String tableName = job.get(OUTPUT_TABLE);
		if (tableName == null) {
			throw new IOException("Must specify table name");
		}
	}
}
