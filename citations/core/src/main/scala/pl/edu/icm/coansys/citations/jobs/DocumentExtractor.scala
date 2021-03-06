package pl.edu.icm.coansys.citations.jobs

import com.nicta.scoobi.Scoobi._
import pl.edu.icm.coansys.models.DocumentProtos.DocumentWrapper
import pl.edu.icm.coansys.citations.data.WireFormats._
import java.io.File
import pl.edu.icm.coansys.citations.util.scoobi

/**
 * @author Mateusz Fedoryszak (m.fedoryszak@icm.edu.pl)
 */


object DocumentExtractor extends ScoobiApp {
  scoobi.addDistCacheJarsToConfiguration(configuration)

  lazy val documentIdPrefix = "doc_"

  def extractDocuments(in: DList[DocumentWrapper]) =
    in.filterNot(_.getDocumentMetadata.getKey.isEmpty)
      .map(x => (documentIdPrefix + x.getDocumentMetadata.getKey, x.getDocumentMetadata.getBasicMetadata))

  def run() {
    val inUri = args(0)
    val outUri = args(1)

    val entities = extractDocuments(convertValueFromSequenceFile[DocumentWrapper](inUri))
    persist(convertToSequenceFile(entities, outUri))
  }
}
