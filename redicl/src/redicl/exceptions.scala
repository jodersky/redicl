package redicl

/** A command error originating from Redis, indicating that an operation did not
  * succeed. */
case class RedisException(message: String) extends Exception(message)

/** The [RESP](https://redis.io/docs/reference/protocol-spec/#resp-bulk-strings)
  * protocol was not followed correctly. This means that some unexpected data
  * was received or a stream closed in the middle of an operation.
  *
  * If this exception occurs, there is not much that can be done. It typically
  * indicates that either something went wrong over the network or that the
  * client and server speak a different version of the protocol (or a bug in the
  * implementation). Usually, the best course of action is to close the client.
  */
case class RedisProtocolException(message: String) extends Exception(message)
