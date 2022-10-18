package redicl

import java.io.InputStream
import java.io.OutputStream
import collection.mutable
import collection.mutable.LinkedHashMap

trait ClientApi extends Types:

  type Key = String
  type FieldKey = String
  type Id = String

  object Redis:

    def apply(
        address: String = "localhost",
        port: Int = 6379,
        acceptTimeoutMs: Int = 5000
    ): Redis =
      import java.net
      val sock = net.Socket()
      val connection =
        sock.connect(net.InetSocketAddress(address, port), acceptTimeoutMs)
      new Redis(sock.getInputStream(), sock.getOutputStream())

  class Redis(in: InputStream, out: OutputStream) extends java.io.Closeable:

    override def close() =
      in.close()
      out.close()

    private val CRLF = "\r\n".getBytes("utf-8")

    // send the Redis server a RESP array consisting of only bulk Strings
    private def send(parts: collection.Seq[BulkString]): Unit =
      out.write('*'.toByte)
      out.write(parts.length.toString().getBytes("utf-8"))
      out.write(CRLF)
      for part <- parts do
        out.write('$'.toByte)
        val length = part.length.toString()
        out.write(length.getBytes("utf-8"))
        out.write(CRLF)
        part.writeBytesTo(out)
        out.write(CRLF)
      out.write(CRLF)

    // a utility buffer for building simple strings
    private val sbuffer = collection.mutable.StringBuilder()

    private var ch: Byte = -1
    private var eof: Boolean = false
    private def read() =
      val c = in.read()
      if c == -1 then eof = true
      ch = c.toByte
      ch

    private def readLong(): Long =
      var n = 0L
      var neg = false
      if ch == '-' then
        neg = true
        read()

      while ch != '\r' && !eof do
        n = 10 * n + (ch - '0'.toByte)
        read()
      read() // \n

      if neg then ~n + 1 else n

    private def receive[A](visitor: Visitor[A]): A =
      read() match
        case -1 =>
          throw RedisProtocolException("stream ended too soon")

        case '+' | '-' =>
          val isError = ch == '-'
          sbuffer.clear()
          read()
          while ch != '\r' && !eof do
            sbuffer.append(ch.toChar)
            read()
          read() // \n
          val str = sbuffer.result()
          if isError then throw RedisException(str)
          else visitor.visitSimpleString(sbuffer)

        case ':' =>
          read()
          visitor.visitNum(readLong())

        case '$' =>
          read()
          val length = readLong()
          if length == -1 then visitor.visitNull()
          else
            val value = visitor.visitBulkString(length, in)
            read() // CR
            read() // LF
            value

        case '*' =>
          read()
          val length = readLong()
          if length < Int.MinValue || length > Int.MaxValue then
            throw RedisProtocolException(
              "implementation limitation: this client only supports reading arrays of size less than 2^31"
            )
          if length == -1 then visitor.visitNull()
          else
            val arrVisitor = visitor.visitArray(length.toInt)
            for i <- 0 until length.toInt do
              arrVisitor.visitIndex(i)
              val value = receive(arrVisitor.subVisitor())
              arrVisitor.visitValue(value)
            arrVisitor.visitEnd()

    /** Run a Redis command directly, as you would with redis-cli.
      *
      * This is a fairly low-level function which accepts any source of bytes,
      * sends them as an array of bulk strings to Redis and reads the result as a
      * RESP type.
      *
      * For a higher-level API which also interprets the result as a meaningful
      * Scala type, see the other methods in this class named after Redis
      * commands.
      */
    def exec(parts: BulkString*): RespValue =
      exec(RespBuilder, parts)

    def exec[A](visitor: Visitor[A], parts: BulkString*): A =
      exec(visitor, parts)

    def exec[A](
        visitor: Visitor[A],
        parts: collection.Seq[BulkString]
    ): A = synchronized {
      send(parts)
      receive(visitor)
    }

    def set[A](key: String, value: Writable): Unit =
      exec(NoopVisitor, "SET", key, value.bs)

    def get[A](key: String)(using r: Reader[A]): Option[A] =
      val visitor = new SimpleVisitor[Option[A]]:
        override def visitBulkString(length: Long, data: InputStream) =
          Some(r.read(length, data))
        override def visitNull() = None
      exec(visitor, "GET", key)

    def xadd(
        key: Key,
        elems: Iterable[(FieldKey, Writable)],
        id: Id = "*"
    ): String =
      val b = mutable.ArrayBuffer.empty[BulkString]
      b += "XADD"
      b += key
      b += id
      for (k, v) <- elems do
        b += k
        b += v.bs
      exec(StringVisitor, b)

    def xlen(key: String): Long =
      exec(LongVisitor, "XLEN", key)

    def xrange[A](key: Key, start: Id, end: Id, count: Long = -1)(using
        r: Reader[A]
    ): LinkedHashMap[Id, LinkedHashMap[FieldKey, A]] =
      val b = mutable.ArrayBuffer.empty[BulkString]
      b += "XRANGE"
      b += key
      b += start
      b += end
      if count != -1 then
        b += "COUNT"
        b += count
      exec(ResultSetVisitor(AttrVisitor(r)), b)

    def xread[A](streams: Iterable[(Key, Id)], count: Int = -1, block: Int = -1)(using r: Reader[A])
      : LinkedHashMap[Key, LinkedHashMap[Id, LinkedHashMap[FieldKey, A]]] =
      val b = mutable.ArrayBuffer.empty[BulkString]
      b += "XREAD"
      if count != -1 then
        b += "COUNT"
        b += count
      if block != -1 then
        b += "BLOCK"
        b += block
      b += "STREAMS"
      for k <- streams.map(_._1) do b += k
      for v <- streams.map(_._2) do b += v
      exec(ResultSetVisitor(ResultSetVisitor(AttrVisitor(r))), b)

    def xgroupCreate(
        key: Key,
        groupName: String,
        id: Id = "$",
        mkStream: Boolean = true
    ): Unit =
      val b = mutable.ArrayBuffer.empty[BulkString]
      b += "XGROUP"
      b += "CREATE"
      b += key
      b += groupName
      b += id
      if mkStream then b += "MKSTREAM"
      exec(NoopVisitor, b)

    def ensureGroup(
        key: Key,
        groupName: String,
        id: Id = "$",
        mkStream: Boolean = true
    ): Unit =
      try
        xgroupCreate(key, groupName, id, mkStream)
      catch
        case ex: RedisException if ex.message.toLowerCase == "busygroup consumer group name already exists" =>

    def xreadGroup[A](
        group: String,
        consumer: String,
        streams: Iterable[(Key, Id)],
        count: Int = -1,
        block: Int = -1
    )(using
        r: Reader[A]
    ): LinkedHashMap[Key, LinkedHashMap[Id, LinkedHashMap[FieldKey, A]]] =
      val b = mutable.ArrayBuffer.empty[BulkString]
      b += "XREADGROUP"
      b += "GROUP"
      b += group
      b += consumer
      if count != -1 then
        b += "COUNT"
        b += count
      if block != -1 then
        b += "BLOCK"
        b += block
      b += "STREAMS"
      for k <- streams.map(_._1) do b += k
      for v <- streams.map(_._2) do b += v
      // key -> id -> field -> value
      exec(ResultSetVisitor(ResultSetVisitor(AttrVisitor(r))), b)

    def xack(key: Key, group: String, id: Id*): Long =
      val b = mutable.ArrayBuffer.empty[BulkString]
      b += "XACK"
      b += key
      b += group
      for i <- id do b += i
      exec(LongVisitor, b)

  end Redis
