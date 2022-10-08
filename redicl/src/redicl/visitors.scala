package redicl

import java.io.InputStream

/** Used for internally parsing results from the Redis server with minimal
  * overhead (i.e. to avoid materializing intermediate data structures).
  *
  * See `Reader[A]` for reading BulkStrings to user-level data types.
  */
trait Visitor[+A]:

  // safe to throw
  def visitSimpleString(data: StringBuilder): A

  // safe to throw
  def visitNum(value: Long): A

  /** Note: an implementation MUST consume exactly `length` bytes (except if the
    * stream is closed), lest the client's state be corrupted. */
  def visitBulkString(length: Long, data: InputStream): A

  // safe to throw
  def visitNull(): A

  /** Note: an implementation MUST visit exactly `length` elements, lest the
    * client's state be corrupted. */
  def visitArray(length: Int): ArrayVisitor[A]

trait ArrayVisitor[+A]:
  def visitIndex(idx: Int): Unit
  def subVisitor(): Visitor[_]
  def visitValue(value: Any): Unit
  def visitEnd(): A

/** A visitor which defaults to throwing a protocol exception.
  * Override the methods you are interested in. */
trait SimpleVisitor[A] extends Visitor[A]:
  def visitSimpleString(data: StringBuilder): A =
    throw RedisProtocolException(s"unexpected data type: simple string")
  def visitNum(value: Long): A =
    throw RedisProtocolException(s"unexpected data type: int")
  def visitBulkString(length: Long, data: InputStream): A =
    throw RedisProtocolException(s"unexpected data type: bulk string")
  def visitNull(): A =
    throw RedisProtocolException(s"unexpected data type: nil")
  def visitArray(length: Int): ArrayVisitor[A] =
    throw RedisProtocolException(s"unexpected data type: array")

object NoopVisitor extends Visitor[Unit] with ArrayVisitor[Unit]:
  def visitSimpleString(data: StringBuilder): Unit = ()
  def visitNum(value: Long): Unit = ()
  def visitBulkString(length: Long, data: InputStream): Unit = ()
  def visitNull(): Unit = ()
  def visitArray(length: Int): ArrayVisitor[Unit] = this
  def visitIndex(idx: Int): Unit = ()
  def visitValue(value: Any): Unit = ()
  def subVisitor() = this
  def visitEnd(): Unit = ()

object RespBuilder extends Visitor[RespValue]:

  override def visitNull(): RespValue = Null

  override def visitSimpleString(data: StringBuilder): RespValue =
    SimpleString(data.result())

  override def visitNum(value: Long): RespValue =
    Num(value)

  override def visitArray(length: Int) = RespArrayBuilder(length)

  override def visitBulkString(length: Long, data: InputStream): RespValue =
    ArrayBulkString(util.readNBytes(length, data))

class RespArrayBuilder(length: Int) extends ArrayVisitor[RespValue]:
  val elems = new Array[RespValue](length)
  var idx = 0
  override def visitIndex(i: Int): Unit = idx = i

  override def subVisitor() = RespBuilder

  override def visitValue(value: Any): Unit =
    elems(idx) = value.asInstanceOf[RespValue]

  override def visitEnd(): RespValue = Arr(elems)

import collection.mutable.LinkedHashMap

// read a Redis bulk string as a Scala string
object StringVisitor extends SimpleVisitor[String]:
  override def visitBulkString(length: Long, data: InputStream): String =
      new String(util.readNBytes(length, data), "utf-8")

object LongVisitor extends SimpleVisitor[Long]:
  override def visitNum(value: Long): Long = value

// parses a nested two-element array as a map
class ResultSetVisitor[A](elementVisitor: Visitor[A]) extends SimpleVisitor[LinkedHashMap[String, A]]:
  val elems = LinkedHashMap.empty[String, A]
  private var key: String = _
  private var isKey: Boolean = false
  object InnerVisitor extends ArrayVisitor[Unit]:
    override def visitIndex(idx: Int): Unit = isKey = !isKey
    override def subVisitor(): Visitor[_] =
      if isKey then StringVisitor else elementVisitor
    override def visitValue(value: Any): Unit =
      if isKey then key = value.asInstanceOf[String]
      else elems += key -> value.asInstanceOf[A]
    override def visitEnd(): Unit = ()

  object ArrVisitor extends SimpleVisitor[Unit]:
    override def visitArray(length: Int): ArrayVisitor[Unit] = InnerVisitor

  override def visitArray(length: Int) = new ArrayVisitor[LinkedHashMap[String, A]]:
    def visitIndex(idx: Int): Unit = ()
    def subVisitor(): Visitor[_] = ArrVisitor
    def visitValue(value: Any): Unit = ()
    def visitEnd(): LinkedHashMap[String, A] = elems
  override def visitNull() = elems

// parses an array alternating between field key and value as a map
class AttrVisitor[A](elementVisitor: Visitor[A]) extends SimpleVisitor[LinkedHashMap[String, A]]:
  val elems = LinkedHashMap.empty[String, A]
  private var key: String = _
  private var isKey: Boolean = false
  object InnerVisitor extends ArrayVisitor[LinkedHashMap[String, A]]:
    override def visitIndex(idx: Int): Unit = isKey = !isKey
    override def subVisitor(): Visitor[_] =
      if isKey then StringVisitor else elementVisitor
    override def visitValue(value: Any): Unit =
      if isKey then key = value.asInstanceOf[String]
      else elems += key -> value.asInstanceOf[A]
    override def visitEnd() = elems

  override def visitArray(length: Int) = InnerVisitor
  override def visitNull() = elems
