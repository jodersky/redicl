package redicl

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream

/** Scala model of the RESP message types.
  *
  * Instances of these classes are only used when a user runs a raw redis
  * command and is interested in the output as Redis returns it (see
  * `Redis.exec`). Most commonly however, messages will be instantiated directly
  * via the `Visitor[A]` and `Reader[A]` mechanisms.
  *
  * The `BulkString` instance is a little special, since it is always used to
  * send messages to the server. Note however, that the base class is simply a
  * forwarder and does not require that any intermediate byte array be
  * allocated.
  *
  * See https://redis.io/docs/reference/protocol-spec/
  */
sealed trait RespValue:

  /** Pretty-print this RESP message instance, just like redis-cli would (except
    * that we use zero-based indexing). */
  def pretty(nesting: Int, out: PrintStream): Unit

  def pretty: String =
    val buffer = ByteArrayOutputStream()
    pretty(0, PrintStream(buffer))
    buffer.toString("utf-8")

  def str = this match
    case SimpleString(value) => value
    case ArrayBulkString(data) =>
      new String(data, "utf-8")
    case bs: BulkString =>
      val b = ByteArrayOutputStream()
      bs.writeBytesTo(b)
      b.toString("utf-8")
    case _ => throw RespValue.InvalidData(this, "Expected simple or bulk string")

  def bytes = this match
    case ArrayBulkString(data) =>
      data
    case bs: BulkString =>
      val b = ByteArrayOutputStream(bs.length.toInt)
      bs.writeBytesTo(b)
      b.toByteArray()
    case _ => throw RespValue.InvalidData(this, "Expected bulk string")

  def bulkStr = this match
    case bs: BulkString => bs
    case _ => throw RespValue.InvalidData(this, "Expected bulk string")

  def bulkStrOpt = this match
    case bs: BulkString => Some(bs)
    case _ => None

  def strOpt = this match
    case _: SimpleString => Some(str)
    case _: BulkString => Some(str)
    case _ => None

  def num = this match
    case Num(n) => n
    case _ => throw RespValue.InvalidData(this, "Expected number")

  def numOpt = this match
    case Num(n) => Some(n)
    case _ => None

  def arr = this match
    case Arr(elems) => elems
    case _ => throw RespValue.InvalidData(this, "Expected array")

  def arrOpt = this match
    case Arr(elems) => Some(elems)
    case _ => None

  def isNull = this match
    case Null => true
    case _ => false

object RespValue:

  case class InvalidData(data: RespValue, msg: String) extends Exception(s"$msg (data: $data)")

/** https://redis.io/docs/reference/protocol-spec/#resp-simple-strings */
case class SimpleString(s: String) extends RespValue:
  def pretty(nesting: Int, out: PrintStream): Unit = out.print(s)

/** https://redis.io/docs/reference/protocol-spec/#resp-errors */
case class Error(message: String) extends RespValue:
  def pretty(nesting: Int, out: PrintStream): Unit = out.print(message)

/** https://redis.io/docs/reference/protocol-spec/#resp-integers */
case class Num(value: Long) extends RespValue:
  def pretty(nesting: Int, out: PrintStream): Unit =
    out.print("(integer) ")
    out.print(value)

/** BulkStrings are always used to send messages to the server. Thus, this
  * class is not only used when materializing messages as explicit
  * `RespValue`s, but also as the argument accepted by `Redis.exec`. Hence, for
  * performance reasons, it is split into the following hierarchy:
  *
  * - This class, which describes the minimal behaviour needed to send data to
  *   Redis but does not specify where the data comes from.
  * - A specialized class `ArrayBulkString` which is backed by a byte array
  *   and used when receiving data from Redis.
  *
  * This separation allows some methods to treat the array-backed instance
  * specially and use the array directly instead of copying memory.
  *
  * https://redis.io/docs/reference/protocol-spec/#resp-bulk-strings
  */
trait BulkString extends RespValue:
  def length: Long
  def writeBytesTo(out: OutputStream): Unit

  // def asArray: Array[Byte] =
  //   val baos = java.io.ByteArrayOutputStream(length.toInt)
  //   writeBytesTo(baos)
  //   baos.toByteArray()

  def pretty(nesting: Int, out: PrintStream): Unit =
    val data = new ByteArrayOutputStream(length.toInt)
    writeBytesTo(data)
    out.print('"')
    out.print(data.toString("utf-8"))
    out.print('"')

/** We want a "natural" API for users calling exec in a REPL, so we provide
  * implicit conversions for a bunch of common types. */
object BulkString:

  given Conversion[String, BulkString] = s =>
    val bytes = s.getBytes("utf-8")
    ArrayBulkString(bytes)

  given [N](using numeric: Numeric[N]): Conversion[N, BulkString] = n =>
    ArrayBulkString(numeric.toLong(n).toString().getBytes())

  given Conversion[Array[Byte], BulkString] = bytes =>
    ArrayBulkString(bytes)

  given Conversion[geny.Writable, BulkString] = w =>
    new BulkString:
      def length = w.contentLength.getOrElse(
        throw RedisProtocolException("only writables of known length can be sent to redis")
      )
      def writeBytesTo(out: OutputStream) = w.writeBytesTo(out)

/** Specialized version of BulkString which is backed by an array. */
case class ArrayBulkString(data: Array[Byte]) extends BulkString:
  def length = data.length
  def writeBytesTo(out: OutputStream): Unit = out.write(data)
  override def pretty(nesting: Int, out: PrintStream): Unit =
    out.print('"')
    out.print(new String(data, "utf-8"))
    out.print('"')

  override def toString =
    val ds = new String(data, "utf-8")
    s"ArrayBulkString($ds)"

  // override def asArray = data

/** Represents null strings as well as null arrays. */
case object Null extends RespValue:
  def pretty(nesting: Int, out: PrintStream): Unit = out.print("(nil)")

/** https://redis.io/docs/reference/protocol-spec/#resp-arrays */
case class Arr(elems: IndexedSeq[RespValue]) extends RespValue:
  def pretty(nesting: Int, out: PrintStream): Unit =
    def digits(n: Int): Int = if n < 10 then 1 else 1 + digits(n / 10)
    val width = digits(elems.length)

    val it = elems.iterator
    var idx = 0
    if it.hasNext then
      out.print(" " * (width - digits(idx)))
      out.print(idx)
      out.print(") ")
      it.next().pretty(nesting + width + 2, out)

      while it.hasNext do
        idx += 1
        out.println()
        for _ <- 0 until nesting do out.print(" ")
        out.print(" " * (width - digits(idx)))
        out.print(idx)
        out.print(") ")
        it.next().pretty(nesting + width + 2, out)
