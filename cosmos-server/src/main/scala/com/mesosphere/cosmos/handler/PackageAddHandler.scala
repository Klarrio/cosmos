package com.mesosphere.cosmos.handler

import com.mesosphere.cosmos.circe.Decoders.decode
import com.mesosphere.cosmos.finch.EndpointHandler
import com.mesosphere.cosmos.http.RequestSession
import com.mesosphere.cosmos.io.ReaderInputStream
import com.mesosphere.cosmos.rpc
import com.mesosphere.cosmos.storage.StagedPackageStorage
import com.mesosphere.cosmos.storage.installqueue.Install
import com.mesosphere.cosmos.storage.installqueue.ProducerView
import com.mesosphere.universe
import com.mesosphere.universe.bijection.UniverseConversions._
import com.mesosphere.universe.v3.circe.Decoders._
import com.twitter.bijection.Conversion.asMethod
import com.twitter.finagle.http.Status
import com.twitter.io.Reader
import com.twitter.io.StreamIO
import com.twitter.util.Future
import com.twitter.util.Try
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

final class PackageAddHandler(
  stagedPackageStorage: StagedPackageStorage,
  producerView: ProducerView
) extends EndpointHandler[rpc.v1.model.AddRequest, rpc.v1.model.AddResponse](
  successStatus = Status.Accepted
) {

  override def apply(request: rpc.v1.model.AddRequest)(implicit
    session: RequestSession
  ): Future[rpc.v1.model.AddResponse] = {
    val packageData = ReaderInputStream(Reader.concat(request.packageData.map(Reader.fromBuf)))
    val Some(contentType) = session.contentType

    for {
      stagedPackageId <- stagedPackageStorage.put(packageData, request.packageSize, contentType)
      v3Package <- readPackageDefinitionFromStorage(stagedPackageId)

      packageCoordinate = rpc.v1.model.PackageCoordinate(v3Package.name, v3Package.version)
      operation = Install(stagedPackageId, v3Package)
      _ <- producerView.add(packageCoordinate, operation)
    } yield {
      new rpc.v1.model.AddResponse(v3Package)
    }
  }

  private[this] def readPackageDefinitionFromStorage(
    stagedPackageId: UUID
  ): Future[universe.v3.model.V3Package] = {
    for {
      // TODO package-add: verify media type
      (_, packageInputStream) <- stagedPackageStorage.get(stagedPackageId)
      packageZip = new ZipInputStream(packageInputStream)
      packageMetadata <- Future.const(extractPackageMetadata(packageZip))
    } yield {
      // TODO package-add: Get creation time from storage
      val timeOfAdd = Instant.now().getEpochSecond
      val releaseVersion = universe.v3.model.PackageDefinition.ReleaseVersion(timeOfAdd).get()
      (packageMetadata, releaseVersion).as[universe.v3.model.V3Package]
    }
  }

  private[this] def extractPackageMetadata(
    packageZip: ZipInputStream
  ): Try[universe.v3.model.Metadata] = {
    Try {
      // TODO package-add: Factor out common Zip-handling code into utility methods
      Iterator
        .continually(Option(packageZip.getNextEntry()))
        .takeWhile(_.isDefined)
        .flatten
        // TODO package-add: Test cases for files with unexpected content
        .filter(_.getName == "metadata.json")
        .map { _ =>
          val metadataBytes = StreamIO.buffer(packageZip).toByteArray
          decode[universe.v3.model.Metadata](new String(metadataBytes, StandardCharsets.UTF_8))
        }
        .next()
    } ensure packageZip.close()
  }

}
