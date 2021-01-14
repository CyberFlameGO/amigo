package models
import org.scalatest.{FlatSpec, Matchers}

class BakeIdTest extends FlatSpec with Matchers {

  "toFilename" should "produce expected filename" in {
    val bakeId = BakeId(RecipeId("puking-pastilles"), 1)
    BakeId.toFilename(bakeId) should be ("puking-pastilles--1.txt")
  }

  "toMetadata" should "produce valid metadata" in {
    val bakeId = BakeId(RecipeId("nosebleed-nougat"), 1)
    BakeId.toMetadata(bakeId) should be ("Recipe=nosebleed-nougat,BuildNumber=1")
  }

}
