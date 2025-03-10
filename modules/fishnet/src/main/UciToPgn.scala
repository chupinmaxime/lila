package lila.fishnet

import cats.data.Validated
import cats.data.Validated.valid
import cats.syntax.all.*
import chess.format.pgn.{ Dumper, SanStr }
import chess.format.Uci
import chess.{ Ply, Drop, Move, Replay, Situation }
import chess.MoveOrDrop.*

import lila.analyse.{ Analysis, Info }
import lila.base.LilaException

// convert variations from UCI to PGN.
// also drops extra variations
private object UciToPgn:

  type WithErrors[A] = (A, List[Exception])

  def apply(replay: Replay, analysis: Analysis): WithErrors[Analysis] =

    val pliesWithAdviceAndVariation: Set[Ply] = analysis.advices.view.collect {
      case a if a.info.hasVariation => a.ply
    }.toSet

    val onlyMeaningfulVariations: List[Info] = analysis.infos map { info =>
      if (pliesWithAdviceAndVariation(info.ply)) info
      else info.dropVariation
    }

    def uciToPgn(ply: Ply, variation: List[String]): Validated[String, List[SanStr]] =
      for {
        situation <-
          if (ply == replay.setup.startedAtPly + 1) valid(replay.setup.situation)
          else replay moveAtPly ply map (_.fold(_.situationBefore, _.situationBefore)) toValid "No move found"
        ucis <- variation.map(Uci.apply).sequence toValid "Invalid UCI moves " + variation
        moves <-
          ucis.foldLeft[Validated[String, (Situation, List[Either[Move, Drop]])]](valid(situation -> Nil)) {
            case (Validated.Valid((sit, moves)), uci: Uci.Move) =>
              sit.move(uci.orig, uci.dest, uci.promotion).leftMap(e => s"ply $ply $e") map { move =>
                move.situationAfter -> (Left(move) :: moves)
              }
            case (Validated.Valid((sit, moves)), uci: Uci.Drop) =>
              sit.drop(uci.role, uci.square).leftMap(e => s"ply $ply $e") map { drop =>
                drop.situationAfter -> (Right(drop) :: moves)
              }
            case (failure, _) => failure
          }
      } yield moves._2.reverse map (_.fold(Dumper.apply, Dumper.apply))

    onlyMeaningfulVariations.foldLeft[WithErrors[List[Info]]]((Nil, Nil)) {
      case ((infos, errs), info) if info.variation.isEmpty => (info :: infos, errs)
      case ((infos, errs), info) =>
        uciToPgn(info.ply, SanStr raw info.variation).fold(
          err => (info.dropVariation :: infos, LilaException(err) :: errs),
          pgn => (info.copy(variation = pgn) :: infos, errs)
        )
    } match
      case (infos, errors) => analysis.copy(infos = infos.reverse) -> errors
