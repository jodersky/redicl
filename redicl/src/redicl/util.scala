package redicl
package util

/** Allocate a new byte array and read data into it.
  *
  * Note: this function has the same name as `java.io.InputStream.readNBytes()`.
  * It behaves similarly, but diverges in two important points:
  * 1. it throws RedisProtocolExceptions in case the stream terminates before
  *    the requested number of bytes could be read
  * 2. `java.io.InputStream.readNBytes()` is not available under Scala native
  */
def readNBytes(length: Long, stream: java.io.InputStream): Array[Byte] =
  if length < Int.MinValue || length > Int.MaxValue then
    throw RedisProtocolException(
      "implementation limitation: this client only supports reading arrays of size less than 2^31"
    )
  val length1 = length.toInt

  val data = new Array[Byte](length1)
  var offset = 0
  var l = 0
  while
    l = stream.read(data, offset, length1 - offset)
    offset < length1 && l != -1
  do
    offset += l

  if offset != length1 then throw RedisProtocolException(
    "stream ended too soon"
  )
  data
