/*
 * Copyright (c) 2019 Mathias Doenitz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package io.bullet.borer.core

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/**
  * Main entry point into the API.
  */
object Cbor {

  /**
    * Entry point into the encoding mini-DSL.
    */
  def encode[T](value: T): EncodingHelper[T, Nothing] = new EncodingHelper(value)

  final class EncodingHelper[T, +Bytes] private[Cbor] (value: T) {
    private[this] var config: Writer.Config                              = Writer.Config.default
    private[this] var validationApplier: Receiver.Applier[Output, Bytes] = Receiver.defaultApplier

    /**
      * Configures the [[Writer.Config]] for this encoding run.
      */
    def withConfig(config: Writer.Config): this.type = {
      this.config = config
      this
    }

    /**
      * Enables logging of the encoding progress to the console.
      * Each data item that is written by the application is pretty printed to the console on its own line.
      */
    def withPrintLogging(maxShownByteArrayPrefixLen: Int = 20, maxShownStringPrefixLen: Int = 50): this.type = {
      withValidationApplier(
        Logging.afterValidation(Logging.PrintLogger(maxShownByteArrayPrefixLen, maxShownStringPrefixLen)))
      this
    }

    /**
      * Enables logging of the encoding progress to the given [[java.lang.StringBuilder]].
      * Each data item that is written by the application is formatted and appended as its own line.
      */
    def withStringLogging(stringBuilder: java.lang.StringBuilder,
                          maxShownByteArrayPrefixLen: Int = 20,
                          maxShownStringPrefixLen: Int = 50,
                          lineSeparator: String = System.lineSeparator()): this.type = {
      withValidationApplier(
        Logging.afterValidation(
          Logging.ToStringLogger(stringBuilder, maxShownByteArrayPrefixLen, maxShownStringPrefixLen, lineSeparator)))
      this
    }

    /**
      * Allows for customizing the injection points around input validation.
      * Used, for example, for on-the-side [[Logging]] of the encoding process.
      */
    def withValidationApplier[By](validationApplier: Receiver.Applier[Output, By]): EncodingHelper[T, By] = {
      this.validationApplier = validationApplier.asInstanceOf[Receiver.Applier[Output, Bytes]]
      this.asInstanceOf[EncodingHelper[T, By]]
    }

    /**
      * Short-cut for encoding to a plain byte array, throwing an exception in case of any failures.
      */
    def toByteArray(implicit ev: Bytes <:< Array[Byte], encoder: Encoder[Array[Byte], T]): Array[Byte] =
      this.asInstanceOf[EncodingHelper[T, Nothing]].to[Array[Byte]].bytes

    /**
      * Short-cut for encoding to a plain byte array, wrapped in a [[Try]] for error handling.
      */
    def toByteArrayTry(implicit ev: Bytes <:< Array[Byte], encoder: Encoder[Array[Byte], T]): Try[Array[Byte]] =
      this.asInstanceOf[EncodingHelper[T, Nothing]].to[Array[Byte]].bytesTry

    /**
      * Encodes an instance of [[T]] to the given `output` using the configures options.
      */
    def to[By >: Bytes](implicit output: Output[By],
                        ba: ByteAccess[By],
                        encoder: Encoder[By, T]): Either[Error[output.Self], output.Self] = {

      val writer = new Writer(output, config, validationApplier.asInstanceOf[Receiver.Applier[Output, By]])
      def out    = writer.output.asInstanceOf[output.Self]
      try {
        encoder.write(writer, value)
        writer.writeEndOfInput() // doesn't actually write but triggers certain validation checks (if configured)
        Right(out)
      } catch {
        case e: Error[_] ⇒ Left(e.asInstanceOf[Error[output.Self]])
        case NonFatal(e) ⇒ Left(new Error.General(out, e))
      }
    }
  }

  /**
    * Entry point into the decoding mini-DSL.
    */
  def decode[Bytes](input: Input[Bytes])(implicit ba: ByteAccess[Bytes]): DecodingHelper[Bytes, input.Self] =
    new DecodingHelper(input)

  final class DecodingHelper[Bytes, In <: Input[Bytes]] private[Cbor] (input: Input[Bytes])(
      implicit ba: ByteAccess[Bytes]) {

    private[this] var prefixOnly: Boolean                               = _
    private[this] var config: Reader.Config                             = Reader.Config.default
    private[this] var validationApplier: Receiver.Applier[Input, Bytes] = Receiver.defaultApplier[Input, Bytes]

    /**
      * Indicated that the decoding process is not expected to consume the complete input.
      */
    def consumePrefix: this.type = {
      this.prefixOnly = true
      this
    }

    /**
      * Configures the [[Reader.Config]] for this decoding run.
      */
    def withConfig(config: Reader.Config): this.type = {
      this.config = config
      this
    }

    /**
      * Enables logging of the decoding progress to the console.
      * Each data item that is consumed from the underlying CBOR stream is pretty printed to the console
      * on its own line.
      */
    def withPrintLogging(maxShownByteArrayPrefixLen: Int = 20, maxShownStringPrefixLen: Int = 50): this.type = {
      withValidationApplier(
        Logging.afterValidation(Logging.PrintLogger(maxShownByteArrayPrefixLen, maxShownStringPrefixLen)))
      this
    }

    /**
      * Enables logging of the decoding progress to the given [[java.lang.StringBuilder]].
      * Each data item that is consumed from the underlying CBOR stream is formatted and appended as its own line.
      */
    def withStringLogging(stringBuilder: java.lang.StringBuilder,
                          maxShownByteArrayPrefixLen: Int = 20,
                          maxShownStringPrefixLen: Int = 50,
                          lineSeparator: String = System.lineSeparator()): this.type = {
      withValidationApplier(
        Logging.afterValidation(
          Logging.ToStringLogger(stringBuilder, maxShownByteArrayPrefixLen, maxShownStringPrefixLen, lineSeparator)))
      this
    }

    /**
      * Allows for customizing the injection points around input validation.
      * Used, for example, for on-the-side [[Logging]] of the decoding process.
      */
    def withValidationApplier(validationApplier: Receiver.Applier[Input, Bytes]): this.type = {
      this.validationApplier = validationApplier
      this
    }

    /**
      * Decodes an instance of [[T]] from the configured `input` using the configures options.
      */
    def to[T](implicit decoder: Decoder[Bytes, T]): Either[Error[In], (T, In)] = {
      val reader = new Reader(input, config, validationApplier)
      def in     = reader.input.asInstanceOf[In]
      try {
        reader.pull() // fetch first data item
        val value = decoder.read(reader)
        if (!prefixOnly) reader.readEndOfInput()
        Right(value → in)
      } catch {
        case e: Error[_] ⇒ Left(e.asInstanceOf[Error[In]])
        case NonFatal(e) ⇒ Left(new Error.General(in, e))
      }
    }
  }

  sealed abstract class Error[IO](val io: IO, msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)

  object Error {
    final class InvalidCborData[IO](io: IO, msg: String) extends Error(io, msg)

    final class ValidationFailure[IO](io: IO, msg: String) extends Error(io, msg)

    final class InsufficientInput[IO](io: IO, val length: Long) extends Error(io, "Insufficient Input")

    final class UnexpectedDataItem[IO](io: IO, expected: String, actual: String)
        extends Error(io, s"Unexpected data item: Expected [$expected] but got [$actual]")

    final class Unsupported[IO](io: IO, msg: String) extends Error(io, msg)

    final class Overflow[IO](io: IO, msg: String) extends Error(io, msg)

    final class General[IO](io: IO, cause: Throwable) extends Error(io, cause.toString, cause)

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    implicit final class EncodingResultOps[Out](val underlying: Either[Error[Out], Out]) extends AnyVal {

      def bytes(implicit ev: BytesOf[Out]): ev.Out = underlying match {
        case Right(out) ⇒ out.asInstanceOf[Output[_]].result().asInstanceOf[ev.Out]
        case Left(e)    ⇒ throw e
      }

      def bytesTry(implicit ev: BytesOf[Out]): Try[ev.Out] = underlying match {
        case Right(out) ⇒ Success(out.asInstanceOf[Output[_]].result().asInstanceOf[ev.Out])
        case Left(e)    ⇒ Failure(e)
      }

      def output: Out = underlying match {
        case Right(out) ⇒ out
        case Left(e)    ⇒ throw e
      }

      def error: Error[Out] = underlying.left.get
    }

    implicit final class DecodingResultOps[In, T](val underlying: Either[Error[In], (T, In)]) extends AnyVal {

      def value: T = underlying match {
        case Right((x, _)) ⇒ x
        case Left(e)       ⇒ throw e
      }

      def valueTry: Try[T] = underlying match {
        case Right((x, _)) ⇒ Success(x)
        case Left(e)       ⇒ Failure(e)
      }

      def error: Error[In] = underlying.left.get
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // helper phantom type
    sealed trait BytesOf[-T] {
      type Out
    }
    implicit def BytesOfOutput[Bytes]: BytesOf[Output[Bytes]] { type Out = Bytes } = null
  }
}
