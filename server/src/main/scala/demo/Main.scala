package demo

import geotrellis.raster._
import geotrellis.raster.io.geotiff._
import geotrellis.raster.render._
import geotrellis.raster.resample._

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.file._
import geotrellis.spark.io.s3._
import geotrellis.spark.io.accumulo._
import geotrellis.spark.io.avro._
import geotrellis.spark.io.avro.codecs._
import geotrellis.spark.io.json._
import geotrellis.spark.io.index._

import org.apache.spark._
import org.apache.avro.Schema

import org.apache.accumulo.core.client.security.tokens._

import akka.actor._
import akka.io.IO
import spray.can.Http
import spray.routing.{HttpService, RequestContext}
import spray.routing.directives.CachingDirectives
import spray.http.MediaTypes
import spray.json._
import spray.json.DefaultJsonProtocol._

import com.typesafe.config.ConfigFactory

import scala.concurrent._
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

object Main {
  val tilesPath = new java.io.File("data/tiles-wm").getAbsolutePath

  /** Usage:
    * First argument is catalog type. Others are dependant on the first argument.
    *
    * local CATALOG_DIR
    * s3 BUCKET_NAME CATALOG_KEY
    * accumulo INSTANCE ZOOKEEPER USER PASSWORD
    */
  def main(args: Array[String]): Unit = {
    implicit val system = akka.actor.ActorSystem("demo-system")

    val conf =
      new SparkConf()
        .setIfMissing("spark.master", "local[*]")
        .setAppName("Demo Server")
        .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .set("spark.kryo.registrator", "geotrellis.spark.io.hadoop.KryoRegistrator")

    implicit val sc = new SparkContext(conf)
    args(0) = "local"
    args(1) = "/Users/rob/proj/workshops/apple/data/landsat-catalog"

    val (layerReader, tileReader, metadataReader) =
      if(args(0) == "local") {
        val localCatalog = args(1)
        val layerReader = FileLayerReader[SpaceTimeKey, MultiBandTile, RasterMetaData](localCatalog)
        val tileReader = new CachingTileReader(FileTileReader[SpaceTimeKey, MultiBandTile](localCatalog))
        val metadataReader =
          new MetadataReader {
            def initialRead(layer: LayerId) = {
              val rmd = layerReader.attributeStore.readLayerAttributes[FileLayerHeader, RasterMetaData, KeyBounds[SpaceTimeKey], KeyIndex[SpaceTimeKey], Unit](layer)._2
              val times = layerReader.attributeStore.read[Array[Long]](LayerId(layer.name, 0), "times")
              LayerMetadata(rmd, times)
            }

            def layerNamesToZooms =
              layerReader.attributeStore.layerIds
                .groupBy(_.name)
                .map { case (name, layerIds) => (name, layerIds.map(_.zoom).sorted.toArray) }
                .toMap
          }

        (layerReader, tileReader, metadataReader)
      } else if(args(0) == "s3"){
        val bucket = args(1)
        val prefix = args(2)

        val layerReader = S3LayerReader[SpaceTimeKey, MultiBandTile, RasterMetaData](bucket, prefix)
        val tileReader = new CachingTileReader(S3TileReader[SpaceTimeKey, MultiBandTile](bucket, prefix))
        val metadataReader =
          new MetadataReader {
            def initialRead(layer: LayerId) = {
              val rmd = layerReader.attributeStore.readLayerAttributes[S3LayerHeader, RasterMetaData, KeyBounds[SpaceTimeKey], KeyIndex[SpaceTimeKey], Schema](layer)._2
              val times = layerReader.attributeStore.read[Array[Long]](LayerId(layer.name, 0), "times")
              LayerMetadata(rmd, times)
            }

            def layerNamesToZooms =
              layerReader.attributeStore.layerIds
                .groupBy(_.name)
                .map { case (name, layerIds) => (name, layerIds.map(_.zoom).sorted.toArray) }
                .toMap
          }

        (layerReader, tileReader, metadataReader)
      } else if(args(0) == "accumulo") {
        val instanceName = args(1)
        val zooKeeper = args(2)
        val user = args(3)
        val password = new PasswordToken(args(4))
        val instance = AccumuloInstance(instanceName, zooKeeper, user, password)
        val layerReader = AccumuloLayerReader[SpaceTimeKey, MultiBandTile, RasterMetaData](instance)
        val tileReader = new CachingTileReader(AccumuloTileReader[SpaceTimeKey, MultiBandTile](instance))
        val metadataReader =
          new MetadataReader {
            def initialRead(layer: LayerId) = {
              val rmd = layerReader.attributeStore.readLayerAttributes[AccumuloLayerHeader, RasterMetaData, KeyBounds[SpaceTimeKey], KeyIndex[SpaceTimeKey], Schema](layer)._2
              val times = layerReader.attributeStore.read[Array[Long]](LayerId(layer.name, 0), "times")
              LayerMetadata(rmd, times)
            }

            def layerNamesToZooms =
              layerReader.attributeStore.layerIds
                .groupBy(_.name)
                .map { case (name, layerIds) => (name, layerIds.map(_.zoom).sorted.toArray) }
                .toMap
          }

        (layerReader, tileReader, metadataReader)
      } else {
        sys.error(s"Unknown catalog type ${args(0)}")
      }

    // create and start our service actor
    val service =
      system.actorOf(Props(classOf[DemoServiceActor], layerReader, tileReader, metadataReader, sc), "demo")

    // start a new HTTP server on port 8088 with our service actor as the handler
    IO(Http) ! Http.Bind(service, "0.0.0.0", 8088)
  }
}