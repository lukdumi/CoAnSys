/*
 * (C) 2010-2012 ICM UW. All rights reserved.
 */

package pl.edu.icm.coansys.citations.util

import org.apache.commons.lang.StringUtils
import pl.edu.icm.coansys.commons.scala.collections
import annotation.tailrec

/**
 * @author Mateusz Fedoryszak (m.fedoryszak@icm.edu.pl)
 */
object author_matching {
  def nonOverlappingMatching(exactMatches: Set[(Int, Int)]) = {
    val len1 = exactMatches.toIterable.unzip._1.max
    val len2 = exactMatches.toIterable.unzip._2.max
    val roughMatchingVal = collections.tabulate[Int](len1 + 1, len2 + 1) {
      (a, i, j) =>
        if (i == 0 || j == 0)
          0
        else
        if (exactMatches((i - 1, j - 1)))
          a(i - 1)(j - 1) + 1
        else
          math.max(a(i - 1)(j), a(i)(j - 1))
    }

    @tailrec
    def extract(i: Int, j: Int, acc: List[(Int, Int)] = Nil): List[(Int, Int)] = {
      if (i > 0 && j > 0) {
        if (exactMatches((i - 1, j - 1))) {
          extract(i - 1, j - 1, (i - 1, j - 1) :: acc)
        } else {
          if (roughMatchingVal(i - 1)(j) >= roughMatchingVal(i)(j - 1))
            extract(i - 1, j, acc)
          else
            extract(i, j - 1, acc)
        }
      }
      else {
        acc
      }
    }

    extract(len1 + 1, len2 + 1)
  }

  /**
   * Returns a list of matching tokens indices. Exact match is used.
   *
   * @return pairs of matching tokens indices
   */
  def exactMatchingTokens(tokens1: List[String], tokens2: List[String]) = for {
    (w, i) <- tokens1.zipWithIndex
    (v, j) <- tokens2.zipWithIndex
    if w == v
  } yield (i, j)


  def matchFactor(tokens1: List[String], tokens2: List[String]) =
    if (!tokens1.isEmpty && !tokens2.isEmpty)
      matchFactorOnNonEmpty(tokens1, tokens2)
    else
    if (tokens1 == tokens2) 1.0 else 0.0

  def matchFactorOnNonEmpty(tokens1: List[String], tokens2: List[String]) = {
    val len1 = tokens1.length
    val len2 = tokens2.length

    //Add begin and end tokens (they always match)
    val extendedExactMatchingTokens =
      exactMatchingTokens(tokens1, tokens2).map {
        case (x, y) => (x + 1, y + 1)
      }.toSet + ((0, 0)) + ((len1 + 1, len2 + 1))

    // Its a matching whose match edges don't overlap
    val nonOverlapping = nonOverlappingMatching(extendedExactMatchingTokens)

    // Average positions of matching tokens
    val boundaries = nonOverlapping.map {
      case (i, j) => (i.toDouble / (len1 + 1) + j.toDouble / (len2 + 1)) / 2
    }

    val alignment = new Alignment(nonOverlapping.unzip._1, nonOverlapping.unzip._2, boundaries)

    val dists = Array.tabulate(len1, len2)(alignment.distance)

    val importance1 = Array.tabulate(len1) {
      i => val v = tokens1(i).length.toDouble / tokens1.mkString("").length; v
    }
    val importance2 = Array.tabulate(len2) {
      i => val v = tokens2(i).length.toDouble / tokens2.mkString("").length; v
    }

    val editDists = Array.tabulate(len1, len2) {
      (i, j) =>
        if (tokens1(i) == tokens2(j))
          0
        else if (((tokens1(i) startsWith tokens2(j)) && (tokens2(j).length <= 2)) ||
          ((tokens2(j) startsWith tokens1(i)) && (tokens1(i).length <= 2)))
          1
        else
          StringUtils.getLevenshteinDistance(tokens1(i), tokens2(j))
    }

    val maxLen = math.max(len1, len2)
    val distTransform: Double => Double = SigmoidFunction.genBetter(0.01, 6.0 / ((len1 + len2) / 2.0))
    val editDistTransform: Int => Double = ((i: Int) => i.toDouble) andThen SigmoidFunction.genBetter(0.01, 4)
    val weights = Array.tabulate(maxLen, maxLen) {
      case (i, j) if i < len1 && j < len2 =>
        distTransform(dists(i)(j)) * editDistTransform(editDists(i)(j)) * (importance1(i) + importance2(j))
      case _ => 0.0
    }
    val maxWeight = weights.flatten.foldLeft(0.0)(_ max _)
    val aleredWeights = Array.tabulate(maxLen, maxLen) {
      (i, j) => maxWeight - weights(i)(j)
    }

    val matching = new HungarianAlgorithm(aleredWeights).execute()

    (matching.zipWithIndex.map {
      case (j, i) => weights(i)(j)
    }.sum) / (importance1.sum + importance2.sum)
  }

  def main(args: Array[String]) {
    val cases = List(
      ("ala ma kota", "kot ma ale"),
      ("Jan Kowalski", "Jan Kowalski"),
      ("J Kowalski", "Jan Kowalski"),
      ("Kowalski", "Jan Kowalski"),
      ("Jan Kowalski", "Kowalski Jan"),
      ("Jan Kowalski", "Józef Kowalski"),
      ("Jan Kowalski", "J Kowalski"),
      ("Jan Kowalski", "Kowalski J"),
      ("Jan Kowalski", "Jan A Kowalski"),
      ("Jan Kowalski", "Kowalski J A"),
      ("Jan Kowalski", "Jan A Kowalski"),
      ("Andrzej Nowak", "Kowalski J A"),
      ("Jan Kowalski Andrzej Nowak", "Kowalski J A Nowak A S"),
      ("Jan Kowalski Andrzej Nowak Krzysztof Tyszkiewicz", "Kowalski J A Nowak A S Tyszkiewicz K J"),
      ("Jan Kowalski Andrzej Nowak Krzysztof Tyszkiewicz", "Kowalski J A Nowak A S Tyszkiewicz K J Banasiak M"),
      ("Jan Kowalski Andrzej Nowak Krzysztof Tyszkiewicz", "Kowalski J A Banasiak M Nowak A S Tyszkiewicz K J"),
      ("Jan Kowalski Andrzej Nowak", "Jan A Kowalski"),
      ("Jan Kowalski Andrzej Kowalski", "Jan A Kowalski"),
      ("Jan Kowalski Andrzej Nowak", "Jan W Kowalski"),
      ("Jan Kowalski Andrzej Kowalski", "Jan W Kowalski"),
      ("Jan Kowalski Andrzej Nowak", "Andrzej Nowak Jan Kowalski"))
    for ((s1, s2) <- cases) {
      print("---%s--- ---%s--- ".format(s1, s2))
      println(matchFactor(s1.split(" ").toList, s2.split(" ").toList))
    }
  }
}
