package fs2
package concurrent

import java.util.concurrent.atomic.AtomicLong

import cats.Eq
import cats.effect.{IO, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._
import TestUtil._
import cats.laws.discipline.{ApplicativeTests, FunctorTests}
import org.scalacheck.Arbitrary
import scala.concurrent.duration._

class SignalSpec extends Fs2Spec {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 10, workers = 1)

  "SignallingRef" - {
    "get/set/discrete" in {
      forAll { (vs0: List[Long]) =>
        val vs = vs0.map { n =>
          if (n == 0) 1 else n
        }
        val s = SignallingRef[IO, Long](0L).unsafeRunSync()
        val r = new AtomicLong(0)
        (IO.shift *> s.discrete.map(r.set).compile.drain).unsafeToFuture()
        assert(vs.forall { v =>
          s.set(v).unsafeRunSync()
          while (s.get.unsafeRunSync() != v) {} // wait for set to arrive
          // can't really be sure when the discrete subscription will be set up,
          // but once we've gotten one update (r != 0), we know we're subscribed
          // and should see result of all subsequent calls to set
          if (r.get != 0) { while (r.get != v) {} }
          true
        })
      }
    }

    "discrete" in {
      // verifies that discrete always receives the most recent value, even when updates occur rapidly
      forAll { (v0: Long, vsTl: List[Long]) =>
        val vs = v0 :: vsTl
        val s = SignallingRef[IO, Long](0L).unsafeRunSync()
        val r = new AtomicLong(0)
        (IO.shift *> s.discrete
          .map { i =>
            Thread.sleep(10); r.set(i)
          }
          .compile
          .drain).unsafeToFuture()
        vs.foreach { v =>
          s.set(v).unsafeRunSync()
        }
        val last = vs.last
        while (r.get != last) {}
        true
      }
    }

    "holdOption" in {
      runLog(Stream.range(1, 10).covary[IO].holdOption)
    }
  }

  /**
    * This is unsafe because the Signal created cannot have multiple consumers
    * of its discrete stream since the source stream is restarted for each
    * consumer.
    *
    * This allows for things like checking whether two Signals converge to the
    * same value, which is important for [[unsafeSignalEquality]].
    *
    * We use this to create finite Signals for testing, namely Signals whose
    * discrete streams terminate and whose gets stop changing after their source
    * streams terminate. Using the usual noneTerminate trick (in this case you'd
    * make the underlying Signal work on Options of values and then
    * unNoneTerminate the discrete stream) makes testing Applicatives painful
    * because it's hard to capture what the last get of the Signal should've
    * been, which we need to ensure that Signals are converging to the same
    * value, since the last get just gets overwritten with a None. So we use
    * this instead.
    */
  private def unsafeHold[F[_]: Sync, A](initial: A, source: Stream[F, A]): F[Signal[F, A]] =
    Ref.of[F, A](initial).map { ref =>
      new Signal[F, A] {
        override def discrete: Stream[F, A] =
          Stream(initial) ++ source.evalTap(ref.set)

        override def continuous: Stream[F, A] = Stream.repeatEval(get)

        override def get: F[A] = ref.get
      }
    }

  /**
    * In order to generate a Signal we have to effectfully run a stream so we
    * need an unsafeRunSync here.
    */
  private implicit def unsafeSignalrbitrary[A: Arbitrary]: Arbitrary[Signal[IO, A]] = {
    val gen = for {
      firstElem <- Arbitrary.arbitrary[A]
      finiteElems <- Arbitrary.arbitrary[List[A]]
    } yield {
      val finiteStream = Stream.emits(finiteElems).covary[IO]
      unsafeHold(firstElem, finiteStream)
    }
    Arbitrary(gen.map(_.unsafeRunSync()))
  }

  private type SignalIO[A] = Signal[IO, A]

  /**
    * We need an instance of Eq for the Discipline laws to work, but actually
    * running a Signal is effectful, so we have to resort to unsafely
    * performing the effect inside the equality check to have a valid Eq
    * instance.
    *
    * Moreover, equality of Signals is kind of murky. Since the exact discrete
    * stream and gets that you see are non-deterministic even if two observers
    * are looking at the same Signal, we need some notion of equality that is
    * robust to this non-determinism.
    *
    * We say that two Signals are equal if they converge to the same value. And
    * two streams converge to the same value if:
    * (1) the "last" element of their discrete streams match after a specified
    *     test timeout, calling get after
    * (2) the last element of the discrete stream results in a match with the
    *     last element
    * (3) the first (or any) element of the continuous stream called after the "last"
    *     element of the discrete stream also matches.
    */
  private implicit def unsafeSignalEquality[A: Eq]: Eq[SignalIO[A]] =
    new Eq[SignalIO[A]] {
      private val timeout = 250.milliseconds
      override def eqv(x: SignalIO[A], y: SignalIO[A]): Boolean = {
        val action = for {
          lastDiscreteXFiber <- x.discrete.interruptAfter(timeout).compile.last.map(_.get).start
          lastDiscreteYFiber <- y.discrete.interruptAfter(timeout).compile.last.map(_.get).start
          lastDiscreteX <- lastDiscreteXFiber.join
          lastDiscreteY <- lastDiscreteYFiber.join
          retrievedX <- x.get
          retrievedY <- y.get
          aContinuousX <- x.continuous.head.compile.last.map(_.get)
          aContinuousY <- y.continuous.head.compile.last.map(_.get)
        } yield {
          val lastDiscretesAreSame = Eq[A].eqv(lastDiscreteX, lastDiscreteY)
          val lastGetsAreTheSame = Eq[A].eqv(retrievedX, retrievedY)
          val continuousAfterGetIsTheSame = Eq[A].eqv(aContinuousX, aContinuousY)
          val lastDiscreteAgreesWithGet = Eq[A].eqv(lastDiscreteX, retrievedX)
          val continuousAfterGetAgreesWithGet = Eq[A].eqv(aContinuousX, retrievedX)

          lastDiscretesAreSame &&
          lastGetsAreTheSame &&
          continuousAfterGetIsTheSame &&
          lastDiscreteAgreesWithGet &&
          continuousAfterGetAgreesWithGet
        }
        action.unsafeRunSync()
      }
    }

  checkAll(
    "Signal (stand-alone functor instance)",
    FunctorTests[SignalIO](Signal.functorInstance).functor[String, Int, Double]
  )

  checkAll(
    "Signal",
    ApplicativeTests[SignalIO].applicative[String, Int, Double]
  )
}
