package io.eels.component.jdbc

import java.sql.{ResultSetMetaData, Types}

import com.sksamuel.exts.Logging
import io.eels.Row
import io.eels.schema._

class GenericJdbcDialect extends JdbcDialect with Logging {

  val config = JdbcReaderConfig()

  override def toJdbcType(field: Field): String = field.dataType match {
    case BigIntType => "int"
    case BinaryType => "binary"
    case BooleanType => "boolean"
    case CharType(size) => s"char($size)"
    case DateType => "date"
    case DecimalType(precision, scale) => s"decimal(${precision.value}, ${scale.value})"
    case DoubleType => "double"
    case FloatType => "float"
    case EnumType(_, _) => "varchar(255)"
    case IntType(_) => "int"
    case LongType(_) => "int"
    case ShortType(_) => "smallint"
    case StringType => "text"
    case TimestampMillisType => "timestamp"
    case TimestampMicrosType => sys.error("Not supported by JDBC")
    case VarcharType(size) =>
      if (size > 0) s"varchar($size)"
      else {
        logger.warn(s"Invalid size $size specified for varchar; defaulting to 255")
        "varchar(255)"
      }
    case _ => sys.error(s"Unsupported data type with JDBC Sink: ${field.dataType}")
  }

  // http://stackoverflow.com/questions/593197/what-is-the-default-precision-and-scale-for-a-number-in-oracle
  private def decimalType(column: Int, metadata: ResultSetMetaData): DataType = {
    // if scale == -127 then it means "any scale" and that can't be supported by hive, so we
    // need to throw it back to the developer to decide what to do (perhaps cast in the sql)
    val precision = metadata.getPrecision(column)
    val scale = metadata.getScale(column)
    if (scale == -127)
      sys.error("Scale is -127 which means 'variable scale' which is not supported, specify a scale in SQL by casting to the appropriate type")
    require(scale <= precision, "Scale must be less than precision")
    DecimalType(
      if (precision <= 0) config.defaultPrecision else precision,
      if (scale < 0) config.defaultScale else scale
    )
  }

  def fromJdbcType(column: Int, metadata: ResultSetMetaData): DataType = metadata.getColumnType(column) match {
    case Types.BIGINT => BigIntType
    case Types.BINARY => BinaryType
    case Types.BIT => BooleanType
    case Types.BLOB => BinaryType
    case Types.BOOLEAN => BooleanType
    case Types.CHAR => CharType(metadata.getPrecision(column))
    case Types.CLOB => StringType
    case Types.DATALINK => throw new UnsupportedOperationException()
    case Types.DATE => DateType
    case Types.DECIMAL => decimalType(column, metadata)
    case Types.DISTINCT => throw new UnsupportedOperationException()
    case Types.DOUBLE => DoubleType
    case Types.FLOAT => FloatType
    case Types.INTEGER => IntType.Signed
    case Types.JAVA_OBJECT => BinaryType
    case Types.LONGNVARCHAR => StringType
    case Types.LONGVARBINARY => BinaryType
    case Types.LONGVARCHAR => StringType
    case Types.NCHAR => StringType
    case Types.NCLOB => StringType
    case Types.NULL => StringType
    case Types.NUMERIC => decimalType(column, metadata)
    case Types.NVARCHAR => StringType
    case Types.OTHER => StringType
    case Types.REAL => DoubleType
    case Types.REF => StringType
    case Types.ROWID => LongType.Signed
    case Types.SMALLINT => ShortType.Signed
    case Types.SQLXML => StringType
    case Types.STRUCT => StringType
    case Types.TIME => TimeMillisType
    case Types.TIMESTAMP => TimestampMillisType
    case Types.TINYINT => ShortType.Signed
    case Types.VARBINARY => BinaryType
    case Types.VARCHAR => VarcharType(metadata.getPrecision(column))
    case _ => StringType
  }

  override def create(schema: StructType, table: String): String = {
    val columns = schema.fields.map { it => s"${it.name} ${toJdbcType(it)}" }.mkString("(", ",", ")")
    s"CREATE TABLE $table $columns"
  }

  override def insertQuery(schema: StructType, table: String): String = {
    val columns = schema.fieldNames().mkString(",")
    val parameters = List.fill(schema.fields.size)("?").mkString(",")
    s"INSERT INTO $table ($columns) VALUES ($parameters)"
  }

  override def insert(row: Row, table: String): String = {
    // todo use proper statements
    val columns = row.schema.fieldNames().mkString(",")
    val values = row.values.mkString("'", "','", "'")
    s"INSERT INTO $table ($columns) VALUES ($values)"
  }
}