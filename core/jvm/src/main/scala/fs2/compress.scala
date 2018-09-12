package fs2

import java.util.zip.{DataFormatException, Deflater, Inflater}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/** Provides utilities for compressing/decompressing byte streams. */
object compress {

  /**
    * Returns a `Pipe` that deflates (compresses) its input elements using
    * a `java.util.zip.Deflater` with the parameters `level`, `nowrap` and `strategy`.
    * @param level the compression level (0-9)
    * @param nowrap if true then use GZIP compatible compression
    * @param bufferSize size of the internal buffer that is used by the
    *                   compressor. Default size is 32 KB.
    * @param strategy compression strategy -- see `java.util.zip.Deflater` for details
    */
  def deflate[F[_]](level: Int = Deflater.DEFAULT_COMPRESSION,
                    nowrap: Boolean = false,
                    bufferSize: Int = 1024 * 32,
                    strategy: Int = Deflater.DEFAULT_STRATEGY): Pipe[F, Byte, Byte] = { in =>
    Pull.suspend {
      val deflater = new Deflater(level, nowrap)
      deflater.setStrategy(strategy)
      val buffer = new Array[Byte](bufferSize)
      _deflate_stream(deflater, buffer)(in)
    }.stream
  }

  private def _deflate_stream[F[_]](deflater: Deflater,
                                    buffer: Array[Byte]): Stream[F, Byte] => Pull[F, Byte, Unit] =
    _.pull.uncons.flatMap {
      case Some((hd, tl)) =>
        deflater.setInput(hd.toArray)
        val result =
          _deflate_collect(deflater, buffer, ArrayBuffer.empty, false).toArray
        Pull.output(Chunk.bytes(result)) >> _deflate_stream(deflater, buffer)(tl)
      case None =>
        deflater.setInput(Array.empty)
        deflater.finish()
        val result =
          _deflate_collect(deflater, buffer, ArrayBuffer.empty, true).toArray
        deflater.end()
        Pull.output(Chunk.bytes(result))
    }

  @tailrec
  private def _deflate_collect(deflater: Deflater,
                               buffer: Array[Byte],
                               acc: ArrayBuffer[Byte],
                               fin: Boolean): ArrayBuffer[Byte] =
    if ((fin && deflater.finished) || (!fin && deflater.needsInput)) acc
    else {
      val count = deflater.deflate(buffer)
      _deflate_collect(deflater, buffer, acc ++ buffer.iterator.take(count), fin)
    }

  /**
    * Returns a `Pipe` that inflates (decompresses) its input elements using
    * a `java.util.zip.Inflater` with the parameter `nowrap`.
    * @param nowrap if true then support GZIP compatible decompression
    * @param bufferSize size of the internal buffer that is used by the
    *                   decompressor. Default size is 32 KB.
    */
  def inflate[F[_]](nowrap: Boolean = false, bufferSize: Int = 1024 * 32)(
      implicit ev: RaiseThrowable[F]): Pipe[F, Byte, Byte] =
    _.pull.uncons.flatMap {
      case None => Pull.pure(None)
      case Some((hd, tl)) =>
        val inflater = new Inflater(nowrap)
        val buffer = new Array[Byte](bufferSize)
        inflater.setInput(hd.toArray)
        val result =
          _inflate_collect(inflater, buffer, ArrayBuffer.empty).toArray
        Pull.output(Chunk.bytes(result)) >> _inflate_stream(inflater, buffer)(ev)(tl)
    }.stream

  private def _inflate_stream[F[_]](inflater: Inflater, buffer: Array[Byte])(
      implicit ev: RaiseThrowable[F]): Stream[F, Byte] => Pull[F, Byte, Unit] =
    _.pull.uncons.flatMap {
      case Some((hd, tl)) =>
        inflater.setInput(hd.toArray)
        val result =
          _inflate_collect(inflater, buffer, ArrayBuffer.empty).toArray
        Pull.output(Chunk.bytes(result)) >> _inflate_stream(inflater, buffer)(ev)(tl)
      case None =>
        if (!inflater.finished)
          Pull.raiseError[F](new DataFormatException("Insufficient data"))
        else { inflater.end(); Pull.done }
    }

  @tailrec
  private def _inflate_collect(inflater: Inflater,
                               buffer: Array[Byte],
                               acc: ArrayBuffer[Byte]): ArrayBuffer[Byte] =
    if (inflater.finished || inflater.needsInput) acc
    else {
      val count = inflater.inflate(buffer)
      _inflate_collect(inflater, buffer, acc ++ buffer.iterator.take(count))
    }
}
