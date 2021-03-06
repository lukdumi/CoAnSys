/*
 * (C) 2010-2012 ICM UW. All rights reserved.
 */

package pl.edu.icm.coansys.citations.jobs

import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.io.{Text, BytesWritable}
import org.apache.hadoop.conf.{Configuration, Configured}
import org.apache.hadoop.util.{ToolRunner, Tool}
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.mapreduce.lib.input.{SequenceFileInputFormat, FileInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{SequenceFileOutputFormat, FileOutputFormat}
import pl.edu.icm.coansys.citations.mappers.{ExactAssesor, HeuristicAdder}
import pl.edu.icm.coansys.citations.reducers.BestSelector

/**
 * @author Mateusz Fedoryszak (m.fedoryszak@icm.edu.pl)
 */
object MatcherLowLevel extends Configured with Tool {

  def run(args: Array[String]): Int = {
    val keyIndexUri = args(0)
    val authorIndexUri = args(1)
    val documentsUri = args(2)
    val outUri = args(3)
    val conf = getConf
    val refsHeuristicUri = outUri + "_heur"
    conf.set("index.key", keyIndexUri)
    conf.set("index.author", authorIndexUri)
    val fs = FileSystem.get(conf)

    val heuristicConf = new Configuration(conf)
    heuristicConf.setInt("mapred.max.split.size", 5 * 1000 * 1000)
    val heuristicJob = new Job(heuristicConf, "Heuristic adder")
    heuristicJob.setJarByClass(getClass)

    FileInputFormat.addInputPath(heuristicJob, new Path(documentsUri))
    FileOutputFormat.setOutputPath(heuristicJob, new Path(refsHeuristicUri))

    heuristicJob.setMapperClass(classOf[HeuristicAdder])
    heuristicJob.setNumReduceTasks(0)
    heuristicJob.setOutputKeyClass(classOf[BytesWritable])
    heuristicJob.setOutputValueClass(classOf[Text])
    heuristicJob.setInputFormatClass(classOf[SequenceFileInputFormat[BytesWritable, BytesWritable]])
    heuristicJob.setOutputFormatClass(classOf[SequenceFileOutputFormat[BytesWritable, Text]])



    if (!heuristicJob.waitForCompletion(true))
      return 1

    val assessorConf = new Configuration(conf)
    val assessorJob = new Job(assessorConf, "Similarity assessor")
    assessorJob.setJarByClass(getClass)

    FileInputFormat.addInputPath(assessorJob, new Path(refsHeuristicUri))
    FileOutputFormat.setOutputPath(assessorJob, new Path(outUri))

    assessorJob.setMapperClass(classOf[ExactAssesor])
    assessorJob.setReducerClass(classOf[BestSelector])
    assessorJob.setCombinerClass(classOf[BestSelector])
    assessorJob.setOutputKeyClass(classOf[Text])
    assessorJob.setOutputValueClass(classOf[Text])
    assessorJob.setInputFormatClass(classOf[SequenceFileInputFormat[BytesWritable, BytesWritable]])
    assessorJob.setOutputFormatClass(classOf[SequenceFileOutputFormat[Text, Text]])

    if (!assessorJob.waitForCompletion(true))
      return 1
    else {
      fs.deleteOnExit(new Path(refsHeuristicUri))
      return 0
    }
  }

  def main(args: Array[String]) {
    val exitCode = ToolRunner.run(MatcherLowLevel, args)
    System.exit(exitCode)
  }
}
