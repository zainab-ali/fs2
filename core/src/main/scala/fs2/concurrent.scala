package fs2

import fs2.async.mutable.Queue
import Stream.Handle

object concurrent {

  /**
    * Joins non deterministically streams.
    *
    *
    * @param maxOpen    Allows to specify maximum open streams at any given time.
    *                   The outer stream will stop its evaluation when this limit is reached and will
    *                   start evaluating once currently open streams will be <= mayOpen.
    *                   MaxOpen must be > 0
    *
    * @param outer      Outer stream, that produces streams (inner) to be run concurrently.
    *                   When this stops gracefully, then all inner streams are continuing to run
    *                   resulting in process that will stop when all `inner` streams finished
    *                   their evaluation.
    *
    *                   When this stream fails, then evaluation of all `inner` streams is interrupted
    *                   and resulting stream will fail with same failure.
    *
    *                   When any of `inner` streams fails, then this stream is interrupted and
    *                   all other `inner` streams are interrupted as well, resulting in stream that fails
    *                   with error of the stream that cased initial failure.
    */
  def join[F[_],O](maxOpen: Int)(s: Stream[F,Stream[F,O]])(implicit F: Async[F]): Stream[F,O] = {
    if (maxOpen <= 0) throw new IllegalArgumentException("maxOpen must be > 0, was: " + maxOpen)
    trait StepResult
    case object Interrupted extends StepResult
    case class NewInnerStream(step: Pull[F,Nothing,Step[Option[Stream[F,O]],Handle[F,Stream[F,O]]]], onForce: Scope[F,Unit])
      extends StepResult
    case class InnerStep(step: Pull[F,Nothing,Step[Chunk[O],Handle[F,O]]], onForce: Scope[F,Unit])
      extends StepResult
    def pumpOuter(q: Queue[F,StepResult])(s: Handle[F,Stream[F,O]]): Pull[F,Nothing,Unit] =
      s.await1Async.flatMap { f =>
        Pull.eval(F.start { F.bind(f.get) { s => q.enqueue1(NewInnerStream(s._1, s._2)) }}) as (())
      }
    def go(outerDone: Boolean, q: Queue[F,StepResult], open: Int): Pull[F,O,Unit] = {
      def pumpInner(s: Handle[F,O]): Pull[F,Nothing,Unit] = s.awaitAsync.flatMap { f =>
        Pull.eval(F.start { F.bind(f.get) { p => q.enqueue1(InnerStep(p._1, p._2)) }}) as (())
      }
      Pull.eval(q.dequeue1).flatMap { // todo - make this dequeue interruptible
        case NewInnerStream(newS,onForce) => Pull.evalScope(onForce) >>
          newS.flatMap {
            case None #: _ => go(outerDone = true, q, open)
            case Some(hd) #: tl => hd.open.flatMap(pumpInner) >> {
              if (open < maxOpen) pumpOuter(q)(tl) >> go(outerDone, q, open + 1)
              else go(outerDone, q, open + 1)
            }
          }
        case InnerStep(winner,onForce) => Pull.evalScope(onForce) >> winner.optional.flatMap {
          case None =>
            if (open == 1 && outerDone) Pull.done // outer stream is done + this is last inner stream to complete
            else go(outerDone, q, open - 1)
          case Some(out #: h) =>
            // winner has more values, write these to output
            // and update the handle for the winning position
            Pull.output(out) >> pumpInner(h) >> go(outerDone, q, open)
        }
        case Interrupted => Pull.done
      }
    }
    Stream.eval(async.unboundedQueue[F,StepResult]).flatMap { q =>
      s.pull { h => pumpOuter(q)(h) >> go(false, q, 0) }.onFinalize(q.enqueue1(Interrupted))
    }
  }
}
