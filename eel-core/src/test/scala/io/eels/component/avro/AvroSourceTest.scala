package io.eels.component.avro

import java.io.File
import java.nio.file.Paths

import io.eels.{Column, FrameSchema}
import org.scalatest.{Matchers, WordSpec}

class AvroSourceTest extends WordSpec with Matchers {

  "AvroSource" should {
    "read schema" in {
      val people = AvroSource(Paths.get(new File(getClass.getResource("/test.avro").getFile).getAbsolutePath))
      people.schema shouldBe FrameSchema(List(Column("name"), Column("job"), Column("location")))
    }
    "read avro files" in {
      val people = AvroSource(Paths.get(new File(getClass.getResource("/test.avro").getFile).getAbsolutePath)).toSeq.run
      people.map(_.map(_.toString)) shouldBe List(
        List("clint eastwood", "actor", "carmel"),
        List("elton john", "musician", "pinner"),
        List("issac newton", "scientist", "heaven")
      )
    }
  }
}

