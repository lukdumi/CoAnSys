/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package pl.edu.icm.coansys.importers.io.writers.hbase;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import static pl.edu.icm.coansys.importers.constants.HBaseConstant.*;
import java.io.IOException;
import java.util.Date;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.pig.backend.executionengine.ExecException;
import pl.edu.icm.coansys.importers.models.DocumentProtosWrapper.DocumentWrapper;

/**
 *
 * @author akawa
 */
public class HBaseToDocumentProtoSequenceFile implements Tool {

    private static Logger logger = Logger.getLogger(HBaseToDocumentProtoSequenceFile.class);
    private Configuration conf;

    @Override
    public void setConf(Configuration conf) {
        this.conf = conf;
    }

    @Override
    public Configuration getConf() {
        return conf;
    }

    public static enum Counters {

        DPROTO, CPROTO, MPROTO
    }

    public static class RowToDocumentProtoMapper extends TableMapper<BytesWritable, BytesWritable> {

        private BytesWritable key = new BytesWritable();
        private BytesWritable documentProto = new BytesWritable();
        private BytesWritable metatdataProto = new BytesWritable();
        private ResultToProtoBytesConverter converter = new ResultToProtoBytesConverter();
        private DocumentWrapper.Builder dw = DocumentWrapper.newBuilder();
        private MultipleOutputs mos = null;
        private int MAX_SIZE = 10000000;

        @Override
        public void setup(Context context) {
            mos = new MultipleOutputs(context);
        }

        @Override
        public void map(ImmutableBytesWritable row, Result values, Context context) throws IOException, InterruptedException {
            converter.set(values, dw);
            if (logger.isDebugEnabled()) {
                logger.debug("reading data from HBase");
            }
            
            byte[] rowId = converter.getRowId();
            byte[] mproto = converter.getDocumentMetadata();
            byte[] cproto = converter.getDocumentMedia();

            if (logger.isDebugEnabled()) {
                logger.debug("converting raw bytes to protocol buffers");
            }
            
            DocumentWrapper documentWrapper = converter.toDocumentWrapper(rowId, mproto, cproto);
            byte[] dproto = documentWrapper.toByteArray();

            key.set(rowId, 0, rowId.length);
            
            if (logger.isDebugEnabled()) {
                logger.debug("writing dproto to output");
            }
            
            if (dproto != null && dproto.length < MAX_SIZE) {
                documentProto.set(dproto, 0, dproto.length);
                mos.write("dproto", key, documentProto);
                context.getCounter(Counters.DPROTO).increment(1);
            }
            
            if (logger.isDebugEnabled()) {
                logger.debug("writing mproto to output");
            }
            
            if (mproto != null) {
                metatdataProto.set(mproto, 0, mproto.length);
                mos.write(FAMILY_METADATA_QUALIFIER_PROTO, key, metatdataProto);
                context.getCounter(Counters.MPROTO).increment(1);
            }

            /*
             if (cproto != null) {
             mediaProto.set(cproto, 0, cproto.length);
             mos.write(FAMILY_CONTENT_QUALIFIER_PROTO, key, mediaProto);
             context.getCounter(Counters.CPROTO).increment(1);
             }
             */
        }

        @Override
        public void cleanup(Context context) throws IOException, InterruptedException {
            mos.close();
        }
    }

    public static class ResultToProtoBytesConverter {

        Result result;
        DocumentWrapper.Builder dw;

        public ResultToProtoBytesConverter() {
        }

        public ResultToProtoBytesConverter(Result result, DocumentWrapper.Builder dw) {
            this.result = result;
            this.dw = dw;
        }

        public void set(Result result, DocumentWrapper.Builder dw) {
            this.result = result;
            this.dw = dw;
        }

        public byte[] getRowId() {
            return result.getRow();
        }

        public DocumentWrapper toDocumentWrapper() throws ExecException, InvalidProtocolBufferException {
            dw.setRowId(ByteString.copyFrom(getRowId()));
            dw.setMproto(ByteString.copyFrom(getDocumentMetadata()));
            dw.setCproto(ByteString.copyFrom(getDocumentMedia()));
            DocumentWrapper build = dw.build();
            return build;
        }

        public DocumentWrapper toDocumentWrapper(byte[] rowid, byte[] mproto, byte[] cproto) throws ExecException, InvalidProtocolBufferException {
            dw.setRowId(ByteString.copyFrom(rowid));
            dw.setMproto(ByteString.copyFrom(mproto));
            dw.setCproto(ByteString.copyFrom(cproto));
            DocumentWrapper build = dw.build();
            return build;
        }

        public byte[] getDocumentMetadata() throws ExecException, InvalidProtocolBufferException {
            return result.getValue(FAMILY_METADATA_BYTES, FAMILY_METADATA_QUALIFIER_PROTO_BYTES);
        }

        public byte[] getDocumentMedia() throws ExecException, InvalidProtocolBufferException {
            return result.getValue(FAMILY_CONTENT_BYTES, FAMILY_CONTENT_QUALIFIER_PROTO_BYTES);
        }
    }

    @Override
    public int run(String[] args) throws Exception {

        String tableName = args[0];
        String outputDir = args[1];

        getOptimizedConfiguration(conf);

        Job job = new Job(conf, HBaseToDocumentProtoSequenceFile.class.getSimpleName() + "_" + tableName + "_" + outputDir);
        job.setJarByClass(HBaseToDocumentProtoSequenceFile.class);

        Scan scan = new Scan();
        scan.setCaching(100);
        scan.setCacheBlocks(false);

        TableMapReduceUtil.initTableMapperJob(tableName, scan, RowToDocumentProtoMapper.class,
                BytesWritable.class, BytesWritable.class, job);
        job.setNumReduceTasks(0);

        job.setOutputKeyClass(BytesWritable.class);
        job.setOutputValueClass(BytesWritable.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);
        SequenceFileOutputFormat.setOutputPath(job, new Path(outputDir));

        MultipleOutputs.addNamedOutput(job, FAMILY_METADATA_QUALIFIER_PROTO, SequenceFileOutputFormat.class, BytesWritable.class, BytesWritable.class);
        MultipleOutputs.addNamedOutput(job, FAMILY_CONTENT_QUALIFIER_PROTO, SequenceFileOutputFormat.class, BytesWritable.class, BytesWritable.class);
        MultipleOutputs.addNamedOutput(job, "dproto", SequenceFileOutputFormat.class, BytesWritable.class, BytesWritable.class);

        boolean success = job.waitForCompletion(true);
        if (!success) {
            throw new IOException("Error with job!");
        }

        return 0;
    }

    private void getOptimizedConfiguration(Configuration conf) {
        conf.set("mapred.child.java.opts", "-Xmx2000m");
        conf.set("io.sort.mb", "500");
        conf.set("io.sort.spill.percent", "0.90");
        conf.set("io.sort.record.percent", "0.15");
    }

    public static void main(String[] args) throws Exception {
        logger.setLevel(Level.ALL);
        if (args == null || args.length == 0) {
            args = new String[2];
            args[0] = "grotoap10";
            args[1] = args[0] + "_dump_" + (new Date()).getTime();
        }

        Configuration conf = HBaseConfiguration.create();
        String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
        if (otherArgs.length < 2) {
            usage("Wrong number of arguments: " + otherArgs.length);
            System.exit(-1);
        }

        int result = ToolRunner.run(conf, new HBaseToDocumentProtoSequenceFile(), args);
        System.exit(result);
    }

    private static void usage(String info) {
        System.out.println(info);
        System.out.println("Exemplary command: ");
        String command = "hadoop jar target/importers-1.0-SNAPSHOT-jar-with-dependencies.jar"
                + " " + HBaseToDocumentProtoSequenceFile.class.getName() + " <table> <directory>";
        System.out.println(command);
    }
}
