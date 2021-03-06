package io.eels.component.parquet

import java.math.{BigInteger, MathContext}
import java.nio.{ByteBuffer, ByteOrder}
import java.sql.{Date, Timestamp}
import java.time.{LocalDateTime, ZoneId}

import com.sksamuel.exts.Logging
import io.eels.schema._
import io.eels.{Row, RowBuilder}
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.column.Dictionary
import org.apache.parquet.hadoop.api.ReadSupport
import org.apache.parquet.hadoop.api.ReadSupport.ReadContext
import org.apache.parquet.io.api._
import org.apache.parquet.schema.MessageType

// required by the parquet reader builder, and returns a record materializer for rows
class RowReadSupport extends ReadSupport[Row] with Logging {

  override def prepareForRead(configuration: Configuration,
                              keyValueMetaData: java.util.Map[String, String],
                              fileSchema: MessageType,
                              readContext: ReadContext): RecordMaterializer[Row] = {
    new RowRecordMaterializer(fileSchema, readContext)
  }

  override def init(configuration: Configuration,
                    keyValueMetaData: java.util.Map[String, String],
                    fileSchema: MessageType): ReadSupport.ReadContext = {
    val projectionSchemaString = configuration.get(ReadSupport.PARQUET_READ_SCHEMA)
    val requestedSchema = ReadSupport.getSchemaForRead(fileSchema, projectionSchemaString)
    logger.debug("Parquet requested schema: " + requestedSchema)
    new ReadSupport.ReadContext(requestedSchema)
  }
}

// a row materializer retrns a group converter which is invoked for each
// field in a group to get a converter for that field, and then each of those
// converts is in turn called with the basic value.
// The converter must know what to do with the basic value so where basic values
// overlap, eg byte arrays, you must have different converters
class RowRecordMaterializer(fileSchema: MessageType,
                            readContext: ReadContext) extends RecordMaterializer[Row] with Logging {

  val schema = ParquetSchemaFns.fromParquetGroupType(readContext.getRequestedSchema)
  logger.debug(s"Record materializer will create row with schema $schema")

  override val getRootConverter: RowGroupConverter = new RowGroupConverter(schema, -1, None)
  override def skipCurrentRecord(): Unit = getRootConverter.builder.reset()
  override def getCurrentRecord: Row = getRootConverter.builder.build()
}

class RowGroupConverter(schema: StructType, index: Int, parent: Option[RowBuilder]) extends GroupConverter with Logging {
  logger.debug(s"Creating group converter for $schema")

  val builder = new RowBuilder(schema)

  def converter(dataType: DataType, fieldIndex: Int): Converter = dataType match {
    case ArrayType(elementType) => converter(elementType, fieldIndex)
    case BinaryType => new DefaultPrimitiveConverter(fieldIndex, builder)
    case BooleanType => new DefaultPrimitiveConverter(fieldIndex, builder)
    case DateType => new DateConverter(fieldIndex, builder)
    case DecimalType(precision, scale) => new DecimalConverter(fieldIndex, builder, precision, scale)
    case DoubleType => new DefaultPrimitiveConverter(fieldIndex, builder)
    case FloatType => new DefaultPrimitiveConverter(fieldIndex, builder)
    case _: IntType => new DefaultPrimitiveConverter(fieldIndex, builder)
    case _: LongType => new DefaultPrimitiveConverter(fieldIndex, builder)
    case _: ShortType => new DefaultPrimitiveConverter(fieldIndex, builder)
    case StringType => new StringConverter(fieldIndex, builder)
    case struct: StructType => new RowGroupConverter(struct, fieldIndex, Option(builder))
    case TimestampMillisType => new TimestampConverter(fieldIndex, builder)
    case other => sys.error("Unsupported type " + other)
  }

  val converters = schema.fields.map(_.dataType).zipWithIndex.map {
    case (dataType, fieldIndex) => converter(dataType, fieldIndex)
  }

  override def getConverter(fieldIndex: Int): Converter = converters(fieldIndex)
  override def end(): Unit = parent.foreach(_.put(index, builder.build.values))
  override def start(): Unit = builder.reset()
}

// just adds the parquet type directly into the builder
// for types that are not pass through, create an instance of a more specialized converter
// we need the index so that we know which fields were present in the file as they will be skipped if null
class DefaultPrimitiveConverter(index: Int, builder: RowBuilder) extends PrimitiveConverter with Logging {
  override def addBinary(value: Binary): Unit = builder.put(index, value.getBytes)
  override def addDouble(value: Double): Unit = builder.put(index, value)
  override def addLong(value: Long): Unit = builder.put(index, value)
  override def addBoolean(value: Boolean): Unit = builder.put(index, value)
  override def addInt(value: Int): Unit = builder.put(index, value)
  override def addFloat(value: Float): Unit = builder.put(index, value)
}

class StringConverter(index: Int,
                      builder: RowBuilder) extends PrimitiveConverter with Logging {

  private var dict: Array[String] = null

  override def addBinary(value: Binary): Unit = builder.put(index, value.toStringUsingUTF8)

  override def hasDictionarySupport: Boolean = true

  override def setDictionary(dictionary: Dictionary): Unit = {
    dict = new Array[String](dictionary.getMaxId + 1)
    for (k <- 0 to dictionary.getMaxId) {
      dict(k) = dictionary.decodeToBinary(k).toStringUsingUTF8
    }
  }

  override def addValueFromDictionary(dictionaryId: Int): Unit = builder.put(index, dict(dictionaryId))
}

// we must use the precision and scale to build the value back from the bytes
class DecimalConverter(index: Int,
                       builder: RowBuilder,
                       precision: Precision,
                       scale: Scale) extends PrimitiveConverter {
  override def addBinary(value: Binary): Unit = {
    val bi = new BigInteger(value.getBytes)
    val bd = BigDecimal.apply(bi, scale.value, new MathContext(precision.value))
    builder.put(index, bd)
  }
}

// https://github.com/Parquet/parquet-mr/issues/218
class TimestampConverter(index: Int, builder: RowBuilder) extends PrimitiveConverter {

  val JulianEpochInGregorian = LocalDateTime.of(-4713, 11, 24, 0, 0, 0)

  override def addBinary(value: Binary): Unit = {
    // first 8 bytes is the nanoseconds
    // second 4 bytes are the days
    val nanos = ByteBuffer.wrap(value.getBytes.slice(0, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong()
    val days = ByteBuffer.wrap(value.getBytes.slice(8, 12)).order(ByteOrder.LITTLE_ENDIAN).getInt()
    val dt = JulianEpochInGregorian.plusDays(days).plusNanos(nanos)
    val millis = dt.atZone(ZoneId.systemDefault).toInstant.toEpochMilli
    builder.put(index, new Timestamp(millis))
  }
}

class DateConverter(index: Int,
                    builder: RowBuilder) extends PrimitiveConverter {

  private val UnixEpoch = LocalDateTime.of(1970, 1, 1, 0, 0, 0)

  override def addInt(value: Int): Unit = {
    val dt = UnixEpoch.plusDays(value)
    val millis = dt.atZone(ZoneId.systemDefault).toInstant.toEpochMilli
    builder.put(index, new Date(millis))
  }
}