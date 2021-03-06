package pl.edu.icm.coansys.citations.jobs.auxiliary

import collection.JavaConversions._
import com.nicta.scoobi.application.ScoobiApp
import com.nicta.scoobi.Scoobi
import com.nicta.scoobi.InputsOutputs._
import com.nicta.scoobi.Persist._
import org.apache.commons.lang.StringUtils
import pl.edu.icm.coansys.citations.util.AugmentedDList.augmentDList
import pl.edu.icm.coansys.citations.indices.EntityIndex
import pl.edu.icm.coansys.citations.util.{libsvm, nlm, XPathEvaluator}
import org.apache.commons.io.IOUtils
import pl.edu.icm.coansys.citations.data.MatchableEntity
import pl.edu.icm.cermine.tools.classification.features.FeatureVectorBuilder
import pl.edu.icm.coansys.citations.data.feature_calculators._

/**
 * @author Mateusz Fedoryszak (m.fedoryszak@icm.edu.pl)
 */
object HeuristicToSvmLight extends ScoobiApp {
  def run() {
    val indexUri = args(0)
    val inUri = args(1)
    val outUri = args(2)

    val results = Scoobi.convertFromSequenceFile[String, String](inUri)
      .flatMapWithResource(new EntityIndex(indexUri)) { case (index, (k, v)) =>
        val parts = v.split("\n", 2)
        val ids = parts(0).split(" ").filterNot(StringUtils.isEmpty).map(_.substring(4)).toSet + k
        val xmlString = parts(1)
        val eval = XPathEvaluator.fromInputStream(IOUtils.toInputStream(xmlString))
        val ref = nlm.referenceMetadataBuilderFromNode(eval.asNode(".")).build()
        val srcCit = MatchableEntity.fromReferenceMetadata(ref)
        val dstDocs = ids.toList.map(id => (id == k, index.getEntityById("doc_" + id)))

        val featureVectorBuilder = new FeatureVectorBuilder[MatchableEntity, MatchableEntity]
        featureVectorBuilder.setFeatureCalculators(List(
          AuthorTrigramMatchFactor,
          AuthorTokenMatchFactor,
          PagesMatchFactor,
          SourceMatchFactor,
          TitleMatchFactor,
          YearMatchFactor))

        (Stream.continually(srcCit) zip dstDocs).map { case (src, (matching, dst)) =>
          val fv = featureVectorBuilder.getFeatureVector(src, dst)
          val label = if (matching) 1 else 0
          libsvm.featureVectorToLibSvmLine(fv, label)
        }
      }

    persist(toTextFile(results, outUri, overwrite = true))
  }
}
