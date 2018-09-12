package fs2
package io
package tcp

import scala.concurrent.duration._

import java.net.{InetSocketAddress, SocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.spi.AsynchronousChannelProvider
import java.nio.channels.{
  AsynchronousChannelGroup,
  AsynchronousCloseException,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import java.util.concurrent.TimeUnit

import cats.implicits._
import cats.effect.{ConcurrentEffect, Resource}
import cats.effect.concurrent.{Ref, Semaphore}

import fs2.Stream._

/**
  * Provides the ability to read/write from a TCP socket in the effect `F`.
  *
  * To construct a `Socket`, use the methods in the [[fs2.io.tcp]] package object.
  */
trait Socket[F[_]] {

  /**
    * Reads up to `maxBytes` from the peer.
    *
    * Evaluates to None, if there are no more bytes to be read in future, due stream reached End-Of-Stream state
    * before returning even single byte. Otherwise returns Some(bytes) with bytes that were ready to be read.
    *
    * If `timeout` is specified, then resulting `F` will evaluate to failure with `java.nio.channels.InterruptedByTimeoutException`
    * if read was not satisfied in given timeout. Read is satisfied, when at least single Byte was received
    * before `timeout` expires.
    *
    * This may return None, as well when end of stream has been reached before timeout expired and no data
    * has been received.
    */
  def read(maxBytes: Int, timeout: Option[FiniteDuration] = None): F[Option[Chunk[Byte]]]

  /**
    * Reads stream of bytes from this socket with `read` semantics. Terminates when eof is received.
    * On timeout, this fails with `java.nio.channels.InterruptedByTimeoutException`.
    */
  def reads(maxBytes: Int, timeout: Option[FiniteDuration] = None): Stream[F, Byte]

  /**
    * Reads exactly `numBytes` from the peer in a single chunk.
    * If `timeout` is provided and no data arrives within the specified duration, then this results in
    * failure with `java.nio.channels.InterruptedByTimeoutException`.
    *
    * When returned size of bytes is < `numBytes` that indicates end-of-stream has been reached.
    */
  def readN(numBytes: Int, timeout: Option[FiniteDuration] = None): F[Option[Chunk[Byte]]]

  /** Indicates that this channel will not read more data. Causes `End-Of-Stream` be signalled to `available`. */
  def endOfInput: F[Unit]

  /** Indicates to peer, we are done writing. **/
  def endOfOutput: F[Unit]

  /** Closes the connection corresponding to this `Socket`. */
  def close: F[Unit]

  /** Asks for the remote address of the peer. */
  def remoteAddress: F[SocketAddress]

  /** Asks for the local address of the socket. */
  def localAddress: F[SocketAddress]

  /**
    * Writes `bytes` to the peer. If `timeout` is provided
    * and the operation does not complete in the specified duration,
    * the returned `Process` fails with a `java.nio.channels.InterruptedByTimeoutException`.
    *
    * Completes when bytes are written to the socket.
    */
  def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration] = None): F[Unit]

  /**
    * Writes the supplied stream of bytes to this socket via `write` semantics.
    */
  def writes(timeout: Option[FiniteDuration] = None): Sink[F, Byte]
}

protected[tcp] object Socket {

  /** see [[fs2.io.tcp.client]] **/
  def client[F[_]](
      to: InetSocketAddress,
      reuseAddress: Boolean,
      sendBufferSize: Int,
      receiveBufferSize: Int,
      keepAlive: Boolean,
      noDelay: Boolean
  )(
      implicit AG: AsynchronousChannelGroup,
      F: ConcurrentEffect[F]
  ): Resource[F, Socket[F]] = {

    def setup: F[AsynchronousSocketChannel] = F.delay {
      val ch =
        AsynchronousChannelProvider.provider().openAsynchronousSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, sendBufferSize)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_KEEPALIVE, keepAlive)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.TCP_NODELAY, noDelay)
      ch
    }

    def connect(ch: AsynchronousSocketChannel): F[AsynchronousSocketChannel] =
      F.async { cb =>
        ch.connect(
          to,
          null,
          new CompletionHandler[Void, Void] {
            def completed(result: Void, attachment: Void): Unit =
              invokeCallback(cb(Right(ch)))
            def failed(rsn: Throwable, attachment: Void): Unit =
              invokeCallback(cb(Left(rsn)))
          }
        )
      }

    Resource.liftF(setup.flatMap(connect)).flatMap(mkSocket(_))
  }

  def server[F[_]](address: InetSocketAddress,
                   maxQueued: Int,
                   reuseAddress: Boolean,
                   receiveBufferSize: Int)(
      implicit AG: AsynchronousChannelGroup,
      F: ConcurrentEffect[F]
  ): Stream[F, Either[InetSocketAddress, Resource[F, Socket[F]]]] = {

    val setup: F[AsynchronousServerSocketChannel] = F.delay {
      val ch = AsynchronousChannelProvider
        .provider()
        .openAsynchronousServerSocketChannel(AG)
      ch.setOption[java.lang.Boolean](StandardSocketOptions.SO_REUSEADDR, reuseAddress)
      ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, receiveBufferSize)
      ch.bind(address)
      ch
    }

    def cleanup(sch: AsynchronousServerSocketChannel): F[Unit] =
      F.delay(if (sch.isOpen) sch.close())

    def acceptIncoming(sch: AsynchronousServerSocketChannel): Stream[F, Resource[F, Socket[F]]] = {
      def go: Stream[F, Resource[F, Socket[F]]] = {
        def acceptChannel: F[AsynchronousSocketChannel] =
          F.async[AsynchronousSocketChannel] { cb =>
            sch.accept(
              null,
              new CompletionHandler[AsynchronousSocketChannel, Void] {
                def completed(ch: AsynchronousSocketChannel, attachment: Void): Unit =
                  invokeCallback(cb(Right(ch)))
                def failed(rsn: Throwable, attachment: Void): Unit =
                  invokeCallback(cb(Left(rsn)))
              }
            )
          }

        eval(acceptChannel.attempt).flatMap {
          case Left(err)       => Stream.empty[F]
          case Right(accepted) => Stream.emit(mkSocket(accepted))
        } ++ go
      }

      go.handleErrorWith {
        case err: AsynchronousCloseException =>
          if (sch.isOpen) Stream.raiseError[F](err)
          else Stream.empty
        case err => Stream.raiseError[F](err)
      }
    }

    Stream
      .bracket(setup)(cleanup)
      .flatMap { sch =>
        Stream.emit(Left(sch.getLocalAddress.asInstanceOf[InetSocketAddress])) ++ acceptIncoming(
          sch)
          .map(Right(_))
      }
  }

  def mkSocket[F[_]](ch: AsynchronousSocketChannel)(
      implicit F: ConcurrentEffect[F]): Resource[F, Socket[F]] = {
    val socket = Semaphore(1).flatMap { readSemaphore =>
      Ref.of[F, ByteBuffer](ByteBuffer.allocate(0)).map { bufferRef =>
        // Reads data to remaining capacity of supplied ByteBuffer
        // Also measures time the read took returning this as tuple
        // of (bytes_read, read_duration)
        def readChunk(buff: ByteBuffer, timeoutMs: Long): F[(Int, Long)] =
          F.async { cb =>
            val started = System.currentTimeMillis()
            ch.read(
              buff,
              timeoutMs,
              TimeUnit.MILLISECONDS,
              (),
              new CompletionHandler[Integer, Unit] {
                def completed(result: Integer, attachment: Unit): Unit = {
                  val took = System.currentTimeMillis() - started
                  invokeCallback(cb(Right((result, took))))
                }
                def failed(err: Throwable, attachment: Unit): Unit =
                  invokeCallback(cb(Left(err)))
              }
            )
          }

        // gets buffer of desired capacity, ready for the first read operation
        // If the buffer does not have desired capacity it is resized (recreated)
        // buffer is also reset to be ready to be written into.
        def getBufferOf(sz: Int): F[ByteBuffer] =
          bufferRef.get.flatMap { buff =>
            if (buff.capacity() < sz)
              F.delay(ByteBuffer.allocate(sz)).flatTap(bufferRef.set)
            else
              F.delay {
                buff.clear()
                buff.limit(sz)
                buff
              }
          }

        // When the read operation is done, this will read up to buffer's position bytes from the buffer
        // this expects the buffer's position to be at bytes read + 1
        def releaseBuffer(buff: ByteBuffer): F[Chunk[Byte]] = F.delay {
          val read = buff.position()
          val result =
            if (read == 0) Chunk.bytes(Array.empty)
            else {
              val dest = new Array[Byte](read)
              buff.flip()
              buff.get(dest)
              Chunk.bytes(dest)
            }
          buff.clear()
          result
        }

        def read0(max: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
          readSemaphore.acquire *>
            F.attempt[Option[Chunk[Byte]]](getBufferOf(max).flatMap { buff =>
                readChunk(buff, timeout.map(_.toMillis).getOrElse(0l)).flatMap {
                  case (read, _) =>
                    if (read < 0) F.pure(None)
                    else releaseBuffer(buff).map(Some(_))
                }
              })
              .flatMap { r =>
                readSemaphore.release *> (r match {
                  case Left(err)         => F.raiseError(err)
                  case Right(maybeChunk) => F.pure(maybeChunk)
                })
              }

        def readN0(max: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
          (readSemaphore.acquire *>
            F.attempt(getBufferOf(max).flatMap { buff =>
              def go(timeoutMs: Long): F[Option[Chunk[Byte]]] =
                readChunk(buff, timeoutMs).flatMap {
                  case (readBytes, took) =>
                    if (readBytes < 0 || buff.position() >= max) {
                      // read is done
                      releaseBuffer(buff).map(Some(_))
                    } else go((timeoutMs - took).max(0))
                }

              go(timeout.map(_.toMillis).getOrElse(0l))
            })).flatMap { r =>
            readSemaphore.release *> (r match {
              case Left(err)         => F.raiseError(err)
              case Right(maybeChunk) => F.pure(maybeChunk)
            })
          }

        def write0(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] = {
          def go(buff: ByteBuffer, remains: Long): F[Unit] =
            F.async[Option[Long]] { cb =>
                val start = System.currentTimeMillis()
                ch.write(
                  buff,
                  remains,
                  TimeUnit.MILLISECONDS,
                  (),
                  new CompletionHandler[Integer, Unit] {
                    def completed(result: Integer, attachment: Unit): Unit =
                      invokeCallback(
                        cb(
                          Right(
                            if (buff.remaining() <= 0) None
                            else Some(System.currentTimeMillis() - start)
                          )))
                    def failed(err: Throwable, attachment: Unit): Unit =
                      invokeCallback(cb(Left(err)))
                  }
                )
              }
              .flatMap {
                case None       => F.pure(())
                case Some(took) => go(buff, (remains - took).max(0))
              }

          go(bytes.toBytes.toByteBuffer, timeout.map(_.toMillis).getOrElse(0l))
        }

        ///////////////////////////////////
        ///////////////////////////////////

        new Socket[F] {
          def readN(numBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
            readN0(numBytes, timeout)
          def read(maxBytes: Int, timeout: Option[FiniteDuration]): F[Option[Chunk[Byte]]] =
            read0(maxBytes, timeout)
          def reads(maxBytes: Int, timeout: Option[FiniteDuration]): Stream[F, Byte] =
            Stream.eval(read(maxBytes, timeout)).flatMap {
              case Some(bytes) =>
                Stream.chunk(bytes) ++ reads(maxBytes, timeout)
              case None => Stream.empty
            }

          def write(bytes: Chunk[Byte], timeout: Option[FiniteDuration]): F[Unit] =
            write0(bytes, timeout)
          def writes(timeout: Option[FiniteDuration]): Sink[F, Byte] =
            _.chunks.flatMap { bs =>
              Stream.eval(write(bs, timeout))
            }

          def localAddress: F[SocketAddress] = F.delay(ch.getLocalAddress)
          def remoteAddress: F[SocketAddress] = F.delay(ch.getRemoteAddress)
          def close: F[Unit] = F.delay(ch.close())
          def endOfOutput: F[Unit] = F.delay { ch.shutdownOutput(); () }
          def endOfInput: F[Unit] = F.delay { ch.shutdownInput(); () }
        }
      }
    }
    Resource.make(socket)(_ => F.delay(if (ch.isOpen) ch.close else ()).attempt.void)
  }
}
