package io.eels.schema

@deprecated("use partition actual")
case class PartitionSpec(parts: Array[PartitionPart]) {

  // returns the partition in normalized directory representation, eg key1=value1/key2=value2/...
  // hive seems to call this the partition name, at least client.listPartitionNames returns these
  def name(): String = parts.map(_.unquoted).mkString("/")

  // from key1=value1/key2=value2 will return key1,key2
  def keys(): Array[String] = parts.map(_.key)

  // from key1=value1/key2=value2 will return List(value1,value2)
  def values(): Array[String] = parts.map(_.value)

  // returns the partition value for the given key
  def get(key: String): String = parts.find(_.key == key).get.value
}

object PartitionSpec {
  val empty = PartitionSpec(Array.empty)
  def parsePath(path: String): PartitionSpec = {
    val parts = path.split("/").map { part =>
      val parts = part.split("=")
      PartitionPart(parts.head, parts.last)
    }
    PartitionSpec(parts)
  }
}

// a single "part" in a partition, ie in country=usa/state=alabama, a value would be state=alabama
case class PartitionPart(key: String, value: String) {

  // returns the key value part in the standard hive key=value format with unquoted values
  def unquoted(): String = s"$key=$value"

  // returns the key value part in the standard hive key=value format with quoted values
  def quoted(): String = s"$key='$value'"
}