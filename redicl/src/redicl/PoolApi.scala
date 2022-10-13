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
