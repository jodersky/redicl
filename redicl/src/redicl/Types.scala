package redicl

import java.io.InputStream

trait Types extends LowPrio:

  /** Visitor that reads a user-facing type from only a bulk string. */
  trait Reader[A] extends SimpleVisitor[A]:
    def read(length: Long, data: InputStream): A
    override def visitBulkString(length: Long, data: InputStream): A =
      read(length, data)

  given Reader[BulkString] with
    def read(length: Long, data: InputStream): BulkString =
      ArrayBulkString(util.readNBytes(length, data))

  /** For all intents and purposes, a writable *is* as BulkString. However, we
    * use a wrapper type defined in an API trait, so that users can define their
    * own conversions. */
  // class Writable(val bs: BulkString)
  opaque type Writable = BulkString
  extension (w: Writable)
    def bs: BulkString = w
  object Writable:
    def apply(bs: BulkString): Writable = bs
    given [A](using f: Conversion[A, BulkString]): Conversion[A, Writable] =
      a => f(a)

trait LowPrio:
  self: Types =>

  given Reader[String] = (length, stream) =>
    new String(util.readNBytes(length, stream), "utf-8")

  given [N](using num: Numeric[N]): Reader[N] = (length, stream) =>
    val s = new String(util.readNBytes(length, stream), "utf-8")
    num.parseString(s).getOrElse(
      throw NumberFormatException(s"$s is not a valid number")
    )
