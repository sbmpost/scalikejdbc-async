package sample

import java.sql.Timestamp
import java.time.Instant
import scala.concurrent._, duration._, ExecutionContext.Implicits.global
import scalikejdbc._, async._
import unit._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MySQLSampleSpec
  extends AnyFlatSpec
  with Matchers
  with DBSettings
  with Logging {

  val column = AsyncLover.column
  val createdTime = Instant.now.plusMillis(123)
  val al = AsyncLover.syntax("al")

  it should "get nano seconds" in {
    val nano = Timestamp.valueOf(java.time.LocalDateTime.now().withNano(789000))
    val id = NamedDB("mysql").autoCommit { implicit s =>
      withSQL {
        insert
          .into(AsyncLover)
          .namedValues(
            column.name -> "Eric",
            column.rating -> 2,
            column.isReactive -> false,
            column.nanotime -> nano,
            column.createdAt -> createdTime
          )
      }.updateAndReturnGeneratedKey.apply()
    }
    val resultFuture = NamedAsyncDB("mysql").withPool { implicit s =>
      withSQL { select.from(AsyncLover as al).where.eq(al.id, id) }
        .map(_.get[Option[java.sql.Timestamp]](al.resultName.nanotime))
        .single
        .future()
    }
    val result = Await.result(resultFuture, 5.seconds)
    result.flatten.map(_.getNanos) should be(Some(nano.getNanos))
  }

  it should "select a single value" in {
    val id = NamedDB("mysql").autoCommit { implicit s =>
      withSQL {
        insert
          .into(AsyncLover)
          .namedValues(
            column.name -> "Eric",
            column.rating -> 2,
            column.isReactive -> false,
            column.createdAt -> createdTime
          )
      }.updateAndReturnGeneratedKey.apply()
    }
    val resultFuture: Future[Option[AsyncLover]] =
      NamedAsyncDB("mysql").withPool { implicit s =>
        withSQL { select.from(AsyncLover as al).where.eq(al.id, id) }
          .map(AsyncLover(al))
          .single
          .future()
      }
    val result = Await.result(resultFuture, 5.seconds)
    result.isDefined should be(true)
  }

  it should "repeat selecting a single value" in {
    val id = NamedDB("mysql").autoCommit { implicit s =>
      withSQL {
        insert
          .into(AsyncLover)
          .namedValues(
            column.name -> "Eric",
            column.rating -> 2,
            column.isReactive -> false,
            column.createdAt -> createdTime
          )
      }.updateAndReturnGeneratedKey.apply()
    }
    def resultFuture: Future[Option[AsyncLover]] =
      NamedAsyncDB("mysql").withPool { implicit s =>
        withSQL { select.from(AsyncLover as al).where.eq(al.id, id) }
          .map(AsyncLover(al))
          .single
          .future()
      }
    val futures = Future.sequence(
      (1 to (asyncConnectionPoolSettings.maxPoolSize + 1)).map(_ =>
        resultFuture
      )
    )
    val results = Await.result(futures, 5.seconds)
    results.foreach(result => result.isDefined should be(true))
  }

  it should "select values as a Iterable" in {
    val resultsFuture: Future[Iterable[AsyncLover]] =
      NamedAsyncDB("mysql").withPool { implicit s =>
        withSQL {
          select
            .from(AsyncLover as al)
            .where
            .isNotNull(
              al.birthday
            ) // mysql-async 0.2.4 cannot parse nullable date value.
            .limit(2)
        }.map(AsyncLover(al)).iterable.future()
      }
    val results = Await.result(resultsFuture, 5.seconds)
    results.size should equal(2)
  }

  it should "select values as a List" in {
    val resultsFuture: Future[List[AsyncLover]] =
      NamedAsyncDB("mysql").withPool { implicit s =>
        withSQL {
          select
            .from(AsyncLover as al)
            .where
            .isNotNull(
              al.birthday
            ) // mysql-async 0.2.4 cannot parse nullable date value.
            .limit(2)
        }.map(AsyncLover(al)).list.future()
      }
    val results = Await.result(resultsFuture, 5.seconds)
    results.size should equal(2)
  }

  it should "read values with convenience methods" in {
    val generatedIdFuture: Future[Long] = NamedAsyncDB("mysql").withPool {
      implicit s =>
        withSQL {
          insert
            .into(AsyncLover)
            .namedValues(
              column.name -> "Eric",
              column.rating -> 2,
              column.isReactive -> false,
              column.createdAt -> createdTime
            )
        }.updateAndReturnGeneratedKey.future()
    }
    // in AsyncLover#apply we are using get with typebinders, specialized getters should work
    val generatedId = Await.result(generatedIdFuture, 5.seconds)
    val created = NamedDB("mysql").readOnly { implicit s =>
      withSQL { select.from(AsyncLover as al).where.eq(al.id, generatedId) }
        .map((rs: WrappedResultSet) => {
          AsyncLover(
            id = rs.get[Long](al.resultName.id),
            name = rs.get[String](al.resultName.name),
            rating = rs.get[Int](al.resultName.rating),
            isReactive = rs.get[Boolean](al.resultName.isReactive),
            lunchtime = rs.get[Option[java.sql.Time]](al.resultName.lunchtime),
            birthday = rs.get[Option[Instant]](al.resultName.lunchtime),
            createdAt = rs.get[Instant](al.resultName.createdAt)
          )
        })
        .single
        .apply()
    }.get
    created.id should equal(generatedId)
    created.name should equal("Eric")
    created.rating should equal(2)
    created.isReactive should be(false)
    created.createdAt should equal(createdTime)
  }

  it should "return generated key" in {
    val generatedIdFuture: Future[Long] = NamedAsyncDB("mysql").withPool {
      implicit s =>
        withSQL {
          insert
            .into(AsyncLover)
            .namedValues(
              column.name -> "Eric",
              column.rating -> 2,
              column.isReactive -> false,
              column.createdAt -> createdTime
            )
          //.returningId
        }.updateAndReturnGeneratedKey.future()
    }
    // the generated key should be found
    val generatedId = Await.result(generatedIdFuture, 5.seconds)
    val created = NamedDB("mysql").readOnly { implicit s =>
      withSQL { select.from(AsyncLover as al).where.eq(al.id, generatedId) }
        .map(AsyncLover(al))
        .single
        .apply()
    }.get
    created.id should equal(generatedId)
    created.name should equal("Eric")
    created.rating should equal(2)
    created.isReactive should be(false)
    created.createdAt should equal(createdTime)
  }

  it should "update" in {
    // updating queries should be successful
    NamedDB("mysql") autoCommit { implicit s =>
      withSQL { delete.from(AsyncLover).where.eq(column.id, 1004) }.update
        .apply()
      withSQL {
        insert
          .into(AsyncLover)
          .namedValues(
            column.id -> 1004,
            column.name -> "Chris",
            column.rating -> 5,
            column.isReactive -> true,
            column.createdAt -> createdTime
          )
      }.update.apply()
    }
    val deletion: Future[Int] = NamedAsyncDB("mysql").withPool { implicit s =>
      withSQL { delete.from(AsyncLover).where.eq(column.id, 1004) }.update
        .future()
    }
    Await.result(deletion, 5.seconds)

    // should be committed
    val deleted = NamedDB("mysql").readOnly { implicit s =>
      withSQL { select.from(AsyncLover as al).where.eq(al.id, 1004) }
        .map(AsyncLover(al))
        .single
        .apply()
    }
    deleted.isDefined should be(false)
  }

  it should "execute" in {
    // execution should be successful
    val id = NamedDB("mysql").autoCommit { implicit s =>
      withSQL {
        insert
          .into(AsyncLover)
          .namedValues(
            column.name -> "Chris",
            column.rating -> 5,
            column.isReactive -> true,
            column.createdAt -> createdTime
          )
      }.updateAndReturnGeneratedKey.apply()
    }
    val deletion: Future[Boolean] = NamedAsyncDB("mysql").withPool {
      implicit s =>
        withSQL { delete.from(AsyncLover).where.eq(column.id, id) }.execute
          .future()
    }
    Await.result(deletion, 5.seconds)

    // should be committed
    val deleted = NamedDB("mysql").readOnly { implicit s =>
      withSQL { select.from(AsyncLover as al).where.eq(al.id, id) }
        .map(AsyncLover(al))
        .single
        .apply()
    }
    deleted.isDefined should be(false)
  }

  it should "update in a local transaction" in {
    (1 to 10).foreach { _ =>

      val generatedIdFuture: Future[Long] = NamedAsyncDB("mysql").localTx {
        implicit tx =>
          withSQL(
            insert
              .into(AsyncLover)
              .namedValues(
                column.name -> "Patric",
                column.rating -> 2,
                column.isReactive -> false,
                column.createdAt -> createdTime
              ) //.returningId
          ).updateAndReturnGeneratedKey.future()
      }
      val generatedId = Await.result(generatedIdFuture, 5.seconds)
      val created = NamedDB("mysql").readOnly { implicit s =>
        withSQL { select.from(AsyncLover as al).where.eq(al.id, generatedId) }
          .map(AsyncLover(al))
          .single
          .apply()
      }.get
      created.id should equal(generatedId)
      created.name should equal("Patric")
      created.rating should equal(2)
      created.isReactive should be(false)
      created.createdAt should equal(createdTime)
    }
  }

  it should "rollback in a local transaction" in {
    (1 to 10).foreach { _ =>

      NamedDB("mysql").autoCommit { implicit s =>
        withSQL { delete.from(AsyncLover).where.eq(column.id, 1003) }.update
          .apply()
      }
      val failureFuture: Future[Unit] = NamedAsyncDB("mysql").localTx {
        implicit tx =>
          import FutureImplicits._
          for {
            _ <- insert
              .into(AsyncLover)
              .namedValues(
                column.id -> 1003,
                column.name -> "Patric",
                column.rating -> 2,
                column.isReactive -> false,
                column.createdAt -> createdTime
              )
            _ <- sql"invalid_query".execute // failure
          } yield ()
      }
      // exception should be thrown
      try {
        Await.result(failureFuture, 5.seconds)
        fail("Exception expected")
      } catch {
        case e: Exception => log.debug("expected", e)
      }
      failureFuture.value.get.isFailure should be(true)

      // should be rolled back
      val notCreated = NamedDB("mysql").readOnly { implicit s =>
        withSQL { select.from(AsyncLover as al).where.eq(al.id, 1003) }
          .map(AsyncLover(al))
          .single
          .apply()
      }
      notCreated.isDefined should be(false)
    }
  }

  it should "provide transaction by AsyncTx.withSQLBuilders" in {
    (1 to 10).foreach { _ =>

      val deletionAndCreation: Future[Seq[AsyncQueryResult]] =
        NamedAsyncDB("mysql").withPool { implicit s =>
          AsyncTx
            .withSQLBuilders(
              delete.from(AsyncLover).where.eq(column.id, 997),
              insert
                .into(AsyncLover)
                .namedValues(
                  column.id -> 997,
                  column.name -> "Eric",
                  column.rating -> 2,
                  column.isReactive -> false,
                  column.createdAt -> createdTime
                )
            )
            .future()
        }
      Await.result(deletionAndCreation, 5.seconds)
      deletionAndCreation.value.get.isSuccess should be(true)
      deletionAndCreation.value.get.get.size should be(2)

      // should be found
      val created = NamedDB("mysql").readOnly { implicit s =>
        withSQL { select.from(AsyncLover as al).where.eq(al.id, 997) }
          .map(AsyncLover(al))
          .single
          .apply()
      }.get
      created.id should equal(997)
      created.name should equal("Eric")
      created.rating should equal(2)
      created.isReactive should be(false)
      created.createdAt should equal(createdTime)
    }
  }

  it should "provide transactional deletion by AsyncTx.withSQLBuilders" in {
    (1 to 10).foreach { _ =>

      NamedDB("mysql").autoCommit { implicit s =>
        withSQL { delete.from(AsyncLover).where.eq(column.id, 998) }.update
          .apply()
      }
      val creationAndDeletion: Future[Seq[AsyncQueryResult]] =
        NamedAsyncDB("mysql").withPool { implicit s =>
          AsyncTx
            .withSQLBuilders(
              insert
                .into(AsyncLover)
                .namedValues(
                  column.id -> 998,
                  column.name -> "Fred",
                  column.rating -> 5,
                  column.isReactive -> true,
                  column.createdAt -> new java.util.Date
                ),
              delete.from(AsyncLover).where.eq(column.id, 998)
            )
            .future()
        }
      Await.result(creationAndDeletion, 5.seconds)
      creationAndDeletion.value.get.isSuccess should be(true)
      creationAndDeletion.value.get.get.size should be(2)

      // should be committed
      val deleted = NamedDB("mysql").readOnly { implicit s =>
        withSQL { select.from(AsyncLover as al).where.eq(al.id, 998) }
          .map(AsyncLover(al))
          .single
          .apply()
      }
      deleted.isDefined should be(false)
    }
  }

  it should "rollback in a transaction when using AsyncTx.withSQLs" in {
    (1 to 10).foreach { _ =>

      val failure: Future[Seq[AsyncQueryResult]] =
        NamedAsyncDB("mysql").withPool { implicit s =>
          AsyncTx
            .withSQLs(
              insert
                .into(AsyncLover)
                .namedValues(
                  column.id -> 999,
                  column.name -> "George",
                  column.rating -> 1,
                  column.isReactive -> false,
                  column.createdAt -> Instant.now
                )
                .toSQL,
              sql"invalid_query" // failure
            )
            .future()
        }
      try {
        Await.result(failure, 5.seconds)
        fail("Exception expected")
      } catch {
        case e: Exception => log.debug("expected", e)
      }
      failure.value.get.isSuccess should be(false)

      val notFound = NamedDB("mysql").readOnly { implicit s =>
        withSQL { select.from(AsyncLover as al).where.eq(al.id, 999) }
          .map(AsyncLover(al))
          .single
          .apply()
      }
      notFound.isDefined should be(false)
    }
  }
}
