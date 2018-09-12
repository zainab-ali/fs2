package fs2.internal

import cats.effect.Sync
import cats.effect.concurrent.Ref
import fs2.Scope

/**
  * Represents a resource acquired during stream interpretation.
  *
  * A resource is acquired by `Algebra.Acquire` and then released by either `Algebra.Release` or
  * `Algebra.CloseScope`.
  *
  * The acquisition of a resource has three steps:
  *
  * 1. A `Resource` instance is created and registered with the current scope (`Algebra.Acquire`)
  * 2. Resource acquisition action is evaluated
  * 3. `acquired` is invoked to confirm acquisition of the resource
  *
  * The reason for this is that during asynchronous stream evaluation, one scope may close the other scope
  * (e.g., merged stream fails while another stream is still acquiring an asynchronous resource).
  * In such a case, a resource may be `released` before `acquired` was evaluated, resulting
  * in an immediate finalization after acquisition is confirmed.
  *
  * A resource may be released by any of the following methods:
  *
  * (1) `Algebra.Release` is interpreted. In this case, the finalizer will be invoked if
  *     the resource was successfully acquired (the `acquired` was evaluated) and resource was not leased to another scope.
  * (2) The owning scope was closed by `Algebra.CloseScope`. This essentially evaluates `release` of
  *     the `Resource` and acts like (1).
  * (3) `acquired` was evaluated after scope was `released` by either (1) or (2). In this case,
  *     finalizer will be invoked immediately if the resource is not leased.
  * (4) `cancel` is invoked on a `Lease` for the resource. This will invoke the finalizer
  *     if the resource was already acquired and released and there are no other outstanding leases.
  *
  * Resources may be leased to other scopes. Each scope must lease with `lease` and  when the other
  * scope is closed (or when the resource lease is no longer required) release the lease with `Lease#cancel`.
  *
  * Note that every method which may potentially call a resource finalizer returns `F[Either[Throwable, Unit]]`
  * instead of `F[Unit]`` to make sure any errors that occur when releasing the resource are properly handled.
  */
private[internal] sealed abstract class Resource[F[_]] {

  /**
    * Id of the resource
    */
  def id: Token

  /**
    * Depending on resource state this will either release resource, or when resource was not yet fully
    * acquired, this will schedule releasing of the resource at earliest opportunity, that is when:
    *
    * (a) The resource finished its acquisition
    *  - and -
    * (b) All scopes borrowing this resource released resource.
    *
    * As a part of release process the resoure's finalizer may be run and in that case it may fail eventually.
    *
    * @return
    */
  def release: F[Either[Throwable, Unit]]

  /**
    * Signals that resource was successfully acquired.
    *
    * If the resource was closed before being acquired, then supplied finalizer is run.
    * That may result in finalizer eventually failing.
    *
    * Yields to true, if the resource's finalizer was successfully acquired and not run, otherwise to false or an error.
    * If the error is yielded then finalizer was run and failed.
    *
    * @param finalizer Finalizer to be run on release is provided.
    * @return
    */
  def acquired(finalizer: F[Unit]): F[Either[Throwable, Boolean]]

  /**
    * Signals that this resource was leased by another scope than one allocating this resource.
    *
    * Yields to `Some(lease)`, if this resource was successfully leased, and scope must bind `lease.cancel` it when not needed anymore.
    * or to `None` when this resource cannot be leased because resource is already released.
    */
  def lease: F[Option[Scope.Lease[F]]]
}

private[internal] object Resource {

  /**
    * State of the resource
    *
    * @param open       Resource is open. At this state resource is either awating its acquisition
    *                   by invoking the `acquired` or is used by Stream.
    * @param finalizer  When resource is successfully acquired, this will contain finalizer that shall be
    *                   invoked when the resource is released.
    * @param leases     References (leases) of this resource
    */
  final case class State[+F[_]](
      open: Boolean,
      finalizer: Option[F[Either[Throwable, Unit]]],
      leases: Int
  )

  val initial = State(open = true, finalizer = None, leases = 0)

  def create[F[_]](implicit F: Sync[F]): Resource[F] =
    new Resource[F] {

      val state: Ref[F, State[F]] = Ref.unsafe(initial)

      val id: Token = new Token

      def release: F[Either[Throwable, Unit]] =
        F.flatMap(state.modify { s =>
          if (s.leases != 0)
            (s.copy(open = false), None) // do not allow to run finalizer if there are leases open
          else
            (s.copy(open = false, finalizer = None), s.finalizer) // reset finalizer to None, will be run, it available, otherwise the acquire will take care of it
        })(finalizer => finalizer.getOrElse(F.pure(Right(()))))

      def acquired(finalizer: F[Unit]): F[Either[Throwable, Boolean]] = {
        val attemptFinalizer = F.attempt(finalizer)
        F.flatten(state.modify { s =>
          if (!s.open && s.leases == 0)
            s -> F.map(attemptFinalizer)(_.right.map(_ => false)) // state is closed and there are no leases, finalizer has to be invoked right away
          else
            s.copy(finalizer = Some(attemptFinalizer)) -> F
              .pure(Right(true)) // either state is open, or leases are present, either release or `Lease#cancel` will run the finalizer
        })
      }

      def lease: F[Option[Scope.Lease[F]]] =
        F.map(state.modify { s =>
          val now = if (!s.open) s else s.copy(leases = s.leases + 1)
          now -> now
        }) { now =>
          if (!now.open) None
          else {
            val lease = new Scope.Lease[F] {
              def cancel: F[Either[Throwable, Unit]] =
                F.flatMap(state.modify { s =>
                  val now = s.copy(leases = s.leases - 1)
                  now -> now
                }) { now =>
                  if (now.open)
                    F.pure(Right(())) // scope is open, we don't have to invoke finalizer
                  else if (now.leases != 0)
                    F.pure(Right(())) // scope is closed, but leases still pending
                  else {
                    // scope is closed and this is last lease, assure finalizer is removed from the state and run
                    F.flatten(state.modify { s =>
                      // previous finalizer shall be alwayy present at this point, this shall invoke it
                      s.copy(finalizer = None) -> s.finalizer.getOrElse(F.pure(Right(())))
                    })
                  }
                }
            }
            Some(lease)
          }
        }
    }
}
