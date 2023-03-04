# A Redis Client

[![project chat](https://img.shields.io/badge/zulip-join_chat-brightgreen.svg)](https://crashbox.zulipchat.com/#narrow/stream/353961-redicl)
[![version](https://img.shields.io/maven-central/v/io.crashbox/redicl_3)](https://search.maven.org/artifact/io.crashbox/redicl_3/)
[![stability: soft](https://img.shields.io/badge/stability-soft-white)](https://www.crashbox.io/stability.html)

A lean redis client implementation that uses only the standard library and is
available for Scala 3 on the JVM and Native.

## Example

```scala
val client = redicl.default.Redis("localhost", 6379)

// Direct interaction, just like redis-cli. Works great in the REPL!
client.exec("SET", "a", 42)
// val res1: redicl.RespValue = SimpleString(OK)

client.exec("GET", "a")
// val res2: redicl.RespValue = ArrayBulkString(42)

// Method wrappers for common commands
client.set("b", "1000")
client.get("b")
// val res4: Option[redicl.RespValue.BulkString] = Some(ArrayBulkString(1000))

// Wrappers for typed access
client.get[String]("b")
// val res5: Option[String] = Some(1000)
client.get[Int]("b")
// val res6: Option[Int] = Some(1000)
```

## Maven Coordinates

```ivy"io.crashbox::redicl::<latest_tag>"```

## How-To

### Connecting to Redis

A connection to a redis server is established by creating an instance of the
`Redis` class.

```scala
val client = redicl.default.Redis("localhost", 6379)
```

Each instance is a `java.io.Closable` which wraps a socket. Thus, one instance
can only be used by one thread at a time.

On the JVM, there are also pooled clients available, which allow you to quickly
create new Redis clients without waiting for the TCP connection to be
established. See `redicl.default.FixedRedisPool` and `redicl.default.RedisPool`.

### Interacting with Redis

The simplest way is to use the `exec()` method of a redis client. This method
mimics the redis-cli tool, and is the most flexible interface. You can give it
any [Redis command](https://redis.io/commands/), and it will respond with an
answer encoded as a [RESP
value](https://redis.io/docs/reference/protocol-spec/).

```scala
client.exec("SET", "a", 42)
// val res1: redicl.RespValue = SimpleString(OK)

client.exec("GET", "a")
// val res2: redicl.RespValue = ArrayBulkString(42)
```

Commands can be strings or any other type that can be encoded as a RESP value.
Responses are represented as a hierarchy of simple case classes, inspired by the
`ujson` library (this means that it's super simple to pattern match or convert
the responses from Redis).

### More Efficient and Richer Deserialization

While the plain `exec()` command is sufficient to fully interact with Redis, it
will always create instances of classes to model the server's answer. This is
acceptable in probably 90% of use-cases, however there are situations in which
you would prefer to directly interpret the answer as some value in your business
logic, and eshew the creation and interpretation of intermediate response
classes.

You can call the overloaded `def exec[A](visitor: Visitor[A], parts:
BulkString*): A` method which takes a `Visitor` as first argument. A visitor is
a class implementing a bunch of callbacks, which can interpret a result directly
without creating intermediate classes.

```scala
client.exec(LongVisitor, "XLEN", "stream1")
// val res: Long = 2
```

Visitors are fundamental in parsing many more complex return types in Redis, for
example the various encodings of associative arrays in commands such as
`XREADGROUP`

Implementing your own visitors is useful for decoding your own custom datatypes
in an application.

### Wrappers for Common Commands

Wrappers around `exec` for some commands are also available. These implement the
corresponding visitors for you, and provide a type-safer interface.

```scala
client.set("b", "1000")
client.get("b")
// val res4: Option[redicl.RespValue.BulkString] = Some(ArrayBulkString(1000))

client.get[String]("b")
// val res5: Option[String] = Some(1000)
client.get[Int]("b")
// val res6: Option[Int] = Some(1000)
```

As of this writing, the number of wrappers is limited. The goal is to implement
wrappers for all Redis commands, however that is done on an as-needed basis
(since there are so many). If you add a new wrapper around a command, please
contribute it back! Otherwise, you can simply fall back to using `exec()` to
call your command.

### API Flavours

You might have noticed that all classes are defined in an "API trait", and hence
they are all part of this strange-looking `redicl.default` object. The reason
for this indirection is to make it easier for you to tweak things and add
support for new datatype encodings in your own applications.

Essentially, many operations require some implicit type classes to deal with
reading and/or writing data. As is common in Scala, you can define your own
typeclasses, however it is tricky to do so in a way that keeps your code clean
(often you want type classes available throughout your app, but you want to
minimize "magic" such as wildcard imports). The approach taken by redicl is to
make typeclasses dependent on the API trait, and ecourage users to define their
own instances of the API trait with new typeclasses.

```scala
object myredicl extends redicl.ClientApi:
  given Reader[MyCustomType] = ...


val defaultClient = redicl.default.Redis("localhost", 6379)
defaultClient.get[MyCustomType]("a") // compile-time error; don't know how to read a MyCustomType

val myClient = myredicl.Redis("localhost", 6379)
myClient.get[MyCustomType]("a") // ok
```

You can use this approach to define typeclasses for a binary format used in your
app, for example to define a way to read and write message in protocol buffers.
