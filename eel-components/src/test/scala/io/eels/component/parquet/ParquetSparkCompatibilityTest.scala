package io.eels.component.parquet

import java.sql.{Date, Timestamp}

import io.eels.Row
import io.eels.schema._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.apache.spark.{SparkConf, SparkContext}
import org.scalatest.{FlatSpec, Matchers}

case class Foo(
                myString: String,
                myDouble: Double,
                myLong: Long,
                myInt: Int,
                myBoolean: Boolean,
                myFloat: Float,
                myShort: Short,
                myDecimal: BigDecimal,
                myBytes: Array[Byte],
                myDate: Date,
                myTimestamp: Timestamp
              )

// a suite of tests designed to ensure that eel parquet support matches the specs, and also that
// it can read/write files that other frameworks (spark, hive, impala, etc) generate.
class ParquetSparkCompatibilityTest extends FlatSpec with Matchers {

  implicit val conf = new Configuration()
  implicit val fs = FileSystem.getLocal(new Configuration())

  val spark = new SparkContext(new SparkConf().setMaster("local").setAppName("sammy"))
  val session = SparkSession.builder().appName("test").master("local").getOrCreate()

  val path = new Path("spark_parquet.parquet")

  fs.delete(path, true)

  // some types default to null in spark, others don't
  val schema = StructType(
    Field("myString", StringType, true),
    Field("myDouble", DoubleType, false),
    Field("myLong", LongType.Signed, false),
    Field("myInt", IntType.Signed, false),
    Field("myBoolean", BooleanType, false),
    Field("myFloat", FloatType, false),
    Field("myShort", ShortType.Signed, false),
    Field("myDecimal", DecimalType(Precision(38), Scale(18)), true),
    Field("myBytes", BinaryType, true),
    Field("myDate", DateType, true),
    Field("myTimestamp", TimestampMillisType, true)
  )

  // create a parquet file using spark local for all supported types and then
  // read back in using eel parquet support and compare
  "parquet reader" should "read spark generated parquet files for all types" in {
    fs.delete(path, true)

    val df = session.sqlContext.createDataFrame(List(
      Foo(
        "wibble",
        13.46D,
        1414L,
        239, // integer
        true,
        1825.5F, // float
        12, // short
        72.72, // big decimal
        Array[Byte](1, 2, 3), // bytes
        new Date(79, 8, 10), // util date
        new Timestamp(1483492808000L) // sql timestamp
      )
    ))

    df.write.mode(SaveMode.Overwrite).parquet(path.toString)

    val frame = ParquetSource(path).toFrame()
    frame.schema shouldBe schema

    val values = frame.collect().head.values.toArray
    // must convert byte array to list for deep equals
    values.update(8, values(8).asInstanceOf[Array[Byte]].toList)
    values shouldBe Vector(
      "wibble",
      13.46D,
      1414L,
      239,
      true,
      1825.5F,
      12,
      BigDecimal(72.72),
      List[Byte](1, 2, 3),
      new Date(79, 8, 10),
      new Timestamp(1483492808000L)
    )

    fs.delete(path, true)
  }

  "parquet writer" should "generate a file compatible with spark" in {
    fs.delete(path, true)

    val row = Row(
      schema,
      "flibble",
      52.972D,
      51616L,
      4536,
      true,
      2466.1F,
      55,
      BigDecimal(95.36),
      List[Byte](3, 1, 3),
      new Date(89, 8, 10),
      new Timestamp(1483492406000L)
    )

    ParquetSink(path).write(Seq(row))

    val df = session.sqlContext.read.parquet(path.toString)
    df.schema shouldBe org.apache.spark.sql.types.StructType(
      Seq(
        StructField("myString", org.apache.spark.sql.types.StringType, true),
        StructField("myDouble", org.apache.spark.sql.types.DoubleType, true),
        StructField("myLong", org.apache.spark.sql.types.LongType, true),
        StructField("myInt", org.apache.spark.sql.types.IntegerType, true),
        StructField("myBoolean", org.apache.spark.sql.types.BooleanType, true),
        StructField("myFloat", org.apache.spark.sql.types.FloatType, true),
        StructField("myShort", org.apache.spark.sql.types.ShortType, true),
        StructField("myDecimal", org.apache.spark.sql.types.DecimalType(38, 18), true),
        StructField("myBytes", org.apache.spark.sql.types.BinaryType, true),
        StructField("myDate", org.apache.spark.sql.types.DateType, true),
        StructField("myTimestamp", org.apache.spark.sql.types.TimestampType, true)
      )
    )

    // must convert byte array to list for deep equals
    val dfvalues = df.collect().head.toSeq.toArray
    dfvalues.update(8, dfvalues(8).asInstanceOf[Array[Byte]].toList)
    // and spark will use java big decimal
    dfvalues.update(7, dfvalues(7).asInstanceOf[java.math.BigDecimal]: BigDecimal)
    dfvalues.toVector shouldBe row.values.toVector
  }
}