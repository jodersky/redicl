package redicl

trait PoolApi:
  self: redicl.RedisApi =>

  /** A fixed-size connection pool.
    *
    * Note: the pool is managed by a simple blocking queue. If this becomes a
    * bottleneck, we should consider using a more concurrent-friendly
    * datastructure. Possible areas of research include HikariCP's ConcurrentBag
    * implementation.
    */
  class FixedRedisPool(
    host: String = "localhost",
    port: Int = 6379,
    size: Int = 8
  ):
    private val clients = java.util.concurrent.LinkedBlockingDeque[Redis]()

    for _ <- 0 until size do clients.add(Redis(host, port))

    def withRedis[A](action: Redis => A): A =
      val client = clients.poll()
      try
        action(client)
      finally
        clients.add(client)

  /** A growable connection pool.
    *
    * This pool starts with an initial number of connections, and can grow if
    * demand exceeds supply.
    *
    * Note: the pool does not shrink back to its initial size after demand
    * spikes! TODO: periodically reclaim memory by removing excessive items from
    * the pool
    */
  class RedisPool(
    host: String = "localhost",
    port: Int = 6379,
    initialSize: Int = 8
  ):
    private val clients = java.util.concurrent.ConcurrentLinkedQueue[Redis]()

    for _ <- 0 until initialSize do clients.add(Redis(host, port))

    def withRedis[A](action: Redis => A): A =
      var client = clients.poll()
      if client == null then
        client = Redis(host, port)

      try
        action(client)
      finally
        clients.add(client)

    /** Current size of the pool. Useful for monitoring purposes. */
    def size(): Int = clients.size()

