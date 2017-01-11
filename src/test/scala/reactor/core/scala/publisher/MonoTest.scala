package reactor.core.scala.publisher

import java.time.{Duration => JDuration}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.concurrent.{Callable, ConcurrentHashMap, TimeUnit, TimeoutException}
import java.util.function.{Consumer, Function, Predicate, Supplier}

import org.reactivestreams.{Publisher, Subscriber, Subscription}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{FreeSpec, Matchers}
import reactor.core.publisher.{BaseSubscriber, Signal, SynchronousSink, Flux => JFlux, Mono => JMono}
import reactor.core.scala.publisher.Mono.just
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import reactor.test.scheduler.VirtualTimeScheduler

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.language.existentials
import scala.math.ScalaNumber
import scala.util.Random

/**
  * Created by winarto on 12/26/16.
  */
class MonoTest extends FreeSpec with Matchers with TableDrivenPropertyChecks {

  private val randomValue = Random.nextLong()
  "Mono" - {
    ".create should create a Mono" in {
      val mono = createMono

      StepVerifier.create(mono)
        .expectNext(randomValue)
        .expectComplete()
        .verify()
    }

    ".defer should create a Mono with deferred Mono" in {
      val mono = Mono.defer(() => createMono)

      StepVerifier.create(mono)
        .expectNext(randomValue)
        .expectComplete()
        .verify()
    }

    ".delay should create a Mono with the first element delayed according to the provided" - {
      "duration" in {
        StepVerifier.withVirtualTime(new Supplier[Mono[Long]] {
          override def get(): Mono[Long] = Mono.delay(Duration(5, TimeUnit.DAYS))
        })
          .thenAwait(JDuration.ofDays(5))
          .expectNextCount(1)
          .expectComplete()
          .verify()
      }

      "duration in millis" in {
        StepVerifier.withVirtualTime(new Supplier[Mono[Long]] {
          override def get(): Mono[Long] = Mono.delayMillis(50000)
        })
          .thenAwait(JDuration.ofSeconds(50))
          .expectNextCount(1)
          .expectComplete()
          .verify()
      }

      "duration in millis with given TimeScheduler" in {
        StepVerifier.withVirtualTime(new Supplier[Mono[Long]] {
          override def get(): Mono[Long] = Mono.delayMillis(50000, VirtualTimeScheduler.enable(false))
        })
          .thenAwait(JDuration.ofSeconds(50))
          .expectNextCount(1)
          .expectComplete()
          .verify()

      }
    }

    ".empty " - {
      "without source should create an empty Mono" in {
        val mono = Mono.empty
        verifyEmptyMono(mono)
      }


      "with source should ignore onNext event and receive completion" in {
        val mono = Mono.empty(createMono)
        verifyEmptyMono(mono)
      }

      def verifyEmptyMono[T](mono: Mono[T]) = {
        StepVerifier.create(mono)
          .expectComplete()
          .verify()
      }
    }

    ".error should create Mono that emit error" in {
      val mono = Mono.error(new RuntimeException("runtime error"))
      StepVerifier.create(mono)
        .expectError(classOf[RuntimeException])
        .verify()
    }

    ".from" - {
      "a publisher should ensure that the publisher will emit 0 or 1 item." in {
        val publisher: JFlux[Int] = JFlux.just(1, 2, 3, 4, 5)

        val mono = Mono.from(publisher)

        StepVerifier.create(mono)
          .expectNext(1)
          .expectComplete()
          .verify()
      }

      "a callable should ensure that Mono will return a value from the Callable" in {
        val callable = new Callable[Long] {
          override def call(): Long = randomValue
        }
        val mono = Mono.fromCallable(callable)
        StepVerifier.create(mono)
          .expectNext(randomValue)
          .expectComplete()
          .verify()
      }

      "a future should result Mono that will return the value from the future object" in {
        import scala.concurrent.ExecutionContext.Implicits.global
        val future = Future[Long] {
          randomValue
        }

        val mono = Mono.fromFuture(future)
        StepVerifier.create(mono)
          .expectNext(randomValue)
          .expectComplete()
          .verify()
      }

      "a Runnable should run the unit within it" in {
        val atomicLong = new AtomicLong()
        val runnable = new Runnable {
          override def run(): Unit = atomicLong.set(randomValue)
        }
        val mono = Mono.fromRunnable(runnable)
        StepVerifier.create(mono)
          .expectComplete()
          .verify()
        atomicLong.get() shouldBe randomValue
      }

      "a Supplier should result Mono that will return the result from supplier" in {
        val mono = Mono.fromSupplier(() => randomValue)
        StepVerifier.create(mono)
          .expectNext(randomValue)
          .expectComplete()
          .verify()
      }
    }

    ".ignoreElements should ignore all elements from a publisher and just react on completion signal" in {
      val mono = Mono.ignoreElements(createMono)
      StepVerifier.create(mono)
        .expectComplete()
        .verify()
    }

    ".just should emit the specified item" in {
      val mono = just(randomValue)
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".justOrEmpty" - {
      "with Option should" - {
        "emit the specified item if the option is not empty" in {
          val mono = Mono.justOrEmpty(Option(randomValue))
          StepVerifier.create(mono)
            .expectNext(randomValue)
            .verifyComplete()
        }
        "just react on completion signal if the option is empty" in {
          val mono = Mono.justOrEmpty(Option.empty)
          StepVerifier.create(mono)
            .expectComplete()
            .verify()
        }
      }
      "with data should" - {
        "emit the specified item if it is not null" in {
          val mono = Mono.justOrEmpty(randomValue)
          StepVerifier.create(mono)
            .expectNext(randomValue)
            .verifyComplete()
        }
      }
    }

    ".never will never signal any data, error or completion signal" in {
      val mono = Mono.never
      StepVerifier.create(mono)
        .expectNoEvent(JDuration.ofMillis(1000))
    }


    ".sequenceEqual should" - {
      "emit Boolean.TRUE when both publisher emit the same value" in {
        val emittedValue = new AtomicBoolean(false)
        val isSubscribed = new AtomicBoolean(false)

        val mono = Mono.sequenceEqual(just(1), just(1))
        mono.subscribe(new BaseSubscriber[Boolean] {
          override def hookOnSubscribe(subscription: Subscription): Unit = {
            subscription.request(1)
            isSubscribed.compareAndSet(false, true)
          }

          override def hookOnNext(value: Boolean): Unit = emittedValue.compareAndSet(false, true)
        })
        //    sequenceEqual has bug with subscription signalling
        //    see https://github.com/reactor/reactor-core/issues/328
        //        TODO: Fix this once the above issue is fixed
        //        isSubscribed shouldBe 'get
        emittedValue shouldBe 'get
      }
    }

    ".when" - {
      "with p1 and p2 should" - {
        "emit tuple2 when both p1 and p2 have emitted the value" in {
          val mono = Mono.when(just(1), just("one"))

          StepVerifier.create(mono)
            .expectNext((1, "one"))
            .verifyComplete()
        }
        "emit error when one of the publisher has error" in {
          val mono = Mono.when(just(1), Mono.error(new RuntimeException()))

          StepVerifier.create(mono)
            .expectError(classOf[RuntimeException])
            .verify()
        }
      }
      "with p1 and p2 and a function combinator (T1, T2) => O should" - {
        "emit O when both p1 and p2 have emitted the value" in {
          val mono = Mono.when(just(1), just("one"), (t1: Int, t2: String) => s"${t1.toString}-$t2")
          StepVerifier.create(mono)
            .expectNext("1-one")
            .verifyComplete()
        }
        "emit error when one of the publisher has error" in {
          val mono = Mono.when(just(1), Mono.error(new RuntimeException()), (t1: Int, t2: String) => s"${t1.toString}-$t2")

          StepVerifier.create(mono)
            .expectError(classOf[RuntimeException])
            .verify()
        }
      }

      "with p1, p2 and p3 should" - {
        "emit tuple3 when all publisher have emitted the value" in {
          val mono = Mono.when(just(1), just(2), just("one-two"))
          StepVerifier.create(mono)
            .expectNext((1, 2, "one-two"))
            .verifyComplete()
        }
        "emit error when one of the publisher encounter error" in {
          val p1 = just(1)
          val p2 = just(2)
          val p3 = just(3)
          val error = Mono.error(new RuntimeException())
          val monos = Table(
            ("p1", "p2", "p3"),
            (p1, p2, error),
            (p1, error, p3),
            (error, p2, p3)
          )
          forAll(monos) { (p1, p2, p3) => {
            val mono = Mono.when(p1, p2, p3)
            StepVerifier.create(mono)
              .expectError(classOf[RuntimeException])
              .verify()
          }
          }
        }
      }

      "with p1, p2, p3 and p4 should" - {
        "emit tuple4 when all publisher have emitted the value" in {
          val mono = Mono.when(just(1), just(2), just("one"), just("two"))
          StepVerifier.create(mono)
            .expectNext((1, 2, "one", "two"))
            .verifyComplete()
        }
        "emit error when one of the publisher encounter error" in {
          val p1 = just(1)
          val p2 = just(2)
          val p3 = just(3)
          val p4 = just(4)
          val error = Mono.error(new RuntimeException())
          val monos = Table(
            ("p1", "p2", "p3", "p4"),
            (p1, p2, p3, error),
            (p1, p2, error, p4),
            (p1, error, p3, p4),
            (error, p2, p3, p4)
          )
          forAll(monos) { (p1, p2, p3, p4) => {
            val mono = Mono.when(p1, p2, p3, p4)
            StepVerifier.create(mono)
              .expectError(classOf[RuntimeException])
              .verify()
          }
          }
        }
      }

      "with p1, p2, p3, p4 and p5 should" - {
        "emit tuple5 when all publisher have emitted the value" in {
          val mono = Mono.when(just(1), just(2), just("one"), just("two"), just("three"))
          StepVerifier.create(mono)
            .expectNext((1, 2, "one", "two", "three"))
            .verifyComplete()
        }
        "emit error when one of the publisher encounter error" in {
          val p1 = just(1)
          val p2 = just(2)
          val p3 = just(3)
          val p4 = just(4)
          val p5 = just(5)
          val error = Mono.error(new RuntimeException())
          val monos = Table(
            ("p1", "p2", "p3", "p4", "p5"),
            (p1, p2, p3, p4, error),
            (p1, p2, p3, error, p5),
            (p1, p2, error, p4, p5),
            (p1, error, p3, p4, p5),
            (error, p2, p3, p4, p5)
          )
          forAll(monos) { (p1, p2, p3, p4, p5) => {
            val mono = Mono.when(p1, p2, p3, p4, p5)
            StepVerifier.create(mono)
              .expectError(classOf[RuntimeException])
              .verify()
          }
          }
        }
      }

      "with p1, p2, p3, p4, p5 and p6 should" - {
        "emit tuple6 when all publisher have emitted the value" in {
          val mono = Mono.when(just(1), just(2), just(3), just("one"), just("two"), just("three"))
          StepVerifier.create(mono)
            .expectNext((1, 2, 3, "one", "two", "three"))
            .verifyComplete()
        }
        "emit error when one of the publisher encounter error" in {
          val p1 = just(1)
          val p2 = just(2)
          val p3 = just(3)
          val p4 = just(4)
          val p5 = just(5)
          val p6 = just(6)
          val error = Mono.error(new RuntimeException())
          val monos = Table(
            ("p1", "p2", "p3", "p4", "p5", "p6"),
            (p1, p2, p3, p4, p5, error),
            (p1, p2, p3, p4, error, p6),
            (p1, p2, p3, error, p5, p6),
            (p1, p2, error, p4, p5, p6),
            (p1, error, p3, p4, p5, p6),
            (error, p2, p3, p4, p5, p6)
          )
          forAll(monos) { (p1, p2, p3, p4, p5, p6) => {
            val mono = Mono.when(p1, p2, p3, p4, p5, p6)
            StepVerifier.create(mono)
              .expectError(classOf[RuntimeException])
              .verify()
          }
          }
        }
      }

      "with iterable" - {
        "of publisher of unit should return when all of the sources has fulfilled" in {
          val completed = new ConcurrentHashMap[String, Boolean]()
          val mono = Mono.when(Iterable(
            just[Unit]({
              completed.put("first", true)
            }),
            just[Unit]({
              completed.put("second", true)
            })
          ))
          StepVerifier.create(mono)
            .expectComplete()
          completed should contain key "first"
          completed should contain key "second"
        }

        "of Mono and combinator function should emit the value after combined by combinator function" in {
          val combinator: (Array[Any] => String) = values => values.map(_.toString).foldLeft("") { (acc, v) => if (acc.isEmpty) v else s"$acc-$v" }
          val mono = Mono.when(Iterable(just[Any](1), just[Any](2)), combinator)
          StepVerifier.create(mono)
            .expectNext("1-2")
            .expectComplete()
            .verify()
        }
      }

      "with varargs of publisher should return when all of the resources has fulfilled" in {
        val completed = new ConcurrentHashMap[String, Boolean]()
        val sources = Seq(just[Unit]({
          completed.put("first", true)
        }),
          just[Unit]({
            completed.put("second", true)
          })
        )
        val mono = Mono.when(sources.toArray: _*)
        StepVerifier.create(mono)
          .expectComplete()
        completed should contain key "first"
        completed should contain key "second"
      }

      "with function combinator and varargs of mono should return when all of the monos has fulfilled" in {

        val combinator: (Array[Any] => String) = { values =>
          values.map(_.toString).foldLeft("") { (acc, value) => if (acc.isEmpty) s"$value" else s"$acc-$value" }
        }

        StepVerifier.create(Mono.when(combinator, just[Any](1), just[Any](2)))
          .expectNext("1-2")
          .expectComplete()
          .verify()
      }
    }

    ".whenDelayError" - {
      "with p1 and p2 should merge when both Monos are fulfilled" in {
        StepVerifier.create(Mono.whenDelayError(just(1), just("one")))
          .expectNext((1, "one"))
          .verifyComplete()
      }

      //      wait till https://github.com/reactor/reactor-core/issues/333 is fixed
      "with p1, p2 and p3 should merge when all Monos are fulfilled" ignore {
        StepVerifier.create(Mono.whenDelayError(just(1), just("one"), just(1L)))
          .expectNext((1, "one", 1L))
          .verifyComplete()
      }

      "with p1, p2, p3 and p4 should merge when all Monos are fulfilled" in {
        StepVerifier.create(Mono.whenDelayError(just(1), just(2), just(3), just(4)))
          .expectNext((1, 2, 3, 4))
          .verifyComplete()
      }

      "with p1, p2, p3, p4 and p5 should merge when all Monos are fulfilled" in {
        StepVerifier.create(Mono.whenDelayError(just(1), just(2), just(3), just(4), just(5)))
          .expectNext((1, 2, 3, 4, 5))
          .verifyComplete()
      }

      "with p1, p2, p3, p4, p5 and p6 should merge when all Monos are fulfilled" in {
        StepVerifier.create(Mono.whenDelayError(just(1), just(2), just(3), just(4), just(5), just(6)))
          .expectNext((1, 2, 3, 4, 5, 6))
          .verifyComplete()
      }

      "with varargs of Publisher[Unit] should be fulfilled when all the underlying sources are fulfilled" in {
        val completed = new ConcurrentHashMap[String, Boolean]()
        val mono = Mono.whenDelayError(
          Seq(
            just[Unit](completed.put("first", true)),
            just[Unit](completed.put("second", true))
          ).toArray: _*
        )
        StepVerifier.create(mono)
          .expectComplete()

        completed should contain key "first"
        completed should contain key "second"
      }

      "with function combinator and varargs of mono should return when all of the monos has fulfilled" in {
        val combinator: (Array[Any] => String) = { values =>
          values.map(_.toString).foldLeft("") { (acc, value) => if (acc.isEmpty) s"$value" else s"$acc-$value" }
        }

        StepVerifier.create(Mono.whenDelayError(combinator, just[Any](1), just[Any](2)))
          .expectNext("1-2")
          .expectComplete()
          .verify()
      }
    }

    ".zip" - {
      val combinator: (Array[Any] => String) = { datas => datas.map(_.toString).foldLeft("") { (acc, v) => if (acc.isEmpty) v else s"$acc-$v" } }
      "with combinator function and varargs of mono should fullfill when all Monos are fulfilled" in {
        val mono = Mono.zip(combinator, just(1), just(2))
        StepVerifier.create(mono)
          .expectNext("1-2")
          .verifyComplete()
      }
      "with combinator function and Iterable of mono should fulfill when all Monos are fulfilled" in {
        val mono = Mono.zip(combinator, Iterable(just(1), just(2)))
        StepVerifier.create(mono)
          .expectNext("1-2")
          .verifyComplete()
      }
    }

    ".as should transform the Mono to whatever the transformer function is provided" in {
      val mono = just(randomValue)

      val flux = mono.as(m => Flux.from(m))
      StepVerifier.create(flux)
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".and" - {
      "should combine this mono and the other" in {
        val mono = just(1) and just(2)
        StepVerifier.create(mono)
          .expectNext((1, 2))
          .verifyComplete()
      }
      "with combinator should combine this mono and the other" in {
        val combinator: (Int, Int) => String = (a, b) => s"$a-$b"
        val mono = just(1).and(just(2), combinator)
        StepVerifier.create(mono)
          .expectNext("1-2")
          .verifyComplete()
      }
      "with rightGenerator should combine the result of this mono and the result of " +
        "mono generated by the right generator" in {
        val mono = Mono.just(1)
          .and[String]((i: Int) => Mono.just(i.toString))
        StepVerifier.create(mono)
          .expectNext((1, "1"))
          .verifyComplete()
      }
      "with rightGenerator and bi-function should combine the result of this mono and the result of monogenerated by the right generator and then combined using " +
        "the bi-function" in {
        val mono = Mono.just(1)
          .and[Int, String]((i: Int) => Mono.just(i * 2), (x: Int, y: Int) => s"${x.toString}-${y.toString}")
      }
    }

    ".awaitOnSubscribe should await onSubscribe" ignore {
      //      TODO: How to test this?
    }

    ".block" - {
      "should block the mono to get the value" in {
        Mono.just(randomValue).block() shouldBe randomValue
      }
      "with duration should block the mono up to the duration" in {
        Mono.just(randomValue).block(Duration(10, TimeUnit.SECONDS)) shouldBe randomValue
      }
      "with millis should block up to the duration" in {
        Mono.just(randomValue).blockMillis(10000) shouldBe randomValue
      }
    }

    ".cast should cast the underlying value" in {
      val number = Mono.just(BigDecimal("123")).cast(classOf[ScalaNumber]).block()
      number shouldBe a[ScalaNumber]
    }

    ".cache should make this Mono a hot source by caching the value" ignore {
      //      TODO: How to test this?
    }

    ".cancelOn should cancel the subscriber on a particular scheduler" ignore {
      //      TODO: How to test this?
    }

    ".compose should defer creating the target mono type" in {
      val mono = Mono.just(1)
      val mono1: Mono[String] = mono.compose[String](m => Flux.from(m.map(_.toString)))

      StepVerifier.create(mono1)
        .expectNext("1")
        .verifyComplete()
    }

    ".concatWith should concatenate mono with another source" in {
      val mono = Mono.just(1)
      StepVerifier.create(mono.concatWith(Mono.just(2)))
        .expectNext(1)
        .expectNext(2)
        .verifyComplete()
    }

    ".defaultIfEmpty should use the provided default value if the mono is empty" in {
      val mono = Mono.empty[Int]
      StepVerifier.create(mono.defaultIfEmpty(-1))
        .expectNext(-1)
        .verifyComplete()
    }

    ".delaySubscription" - {
      "with delay duration should delay subscription as long as the provided duration" in {
        StepVerifier.withVirtualTime(new Supplier[Mono[Int]] {
          override def get(): Mono[Int] = Mono.just(1).delaySubscription(Duration(1, TimeUnit.HOURS))
        })
          .thenAwait(JDuration.ofHours(1))
          .expectNext(1)
          .verifyComplete()
      }
      "with another publisher should delay the current subscription until the other publisher completes" in {
        StepVerifier.withVirtualTime(new Supplier[Mono[Int]] {
          override def get(): Mono[Int] = Mono.just(1).delaySubscription(Mono.just("one").delaySubscription(Duration(1, TimeUnit.HOURS)))
        })
          .thenAwait(JDuration.ofHours(1))
          .expectNext(1)
          .verifyComplete()

      }
    }

    ".delaySubscriptionMillis" - {
      "with delay duration in millis should delay subscription as long as the provided duration" in {
        StepVerifier.withVirtualTime(new Supplier[Mono[Int]] {
          override def get(): Mono[Int] = Mono.just(1).delaySubscriptionMillis(60000)
        })
          .thenAwait(JDuration.ofMinutes(10))
          .expectNext(1)
          .verifyComplete()
      }
      "with delay duration in millis and timed scheduler should delay subscription as long as the provided duration using the provided scheduler" in {
        StepVerifier.withVirtualTime(new Supplier[Mono[Int]] {
          override def get(): Mono[Int] = {
            Mono.just(1).delaySubscriptionMillis(60000, Schedulers.timer())
          }
        })
          .thenAwait(JDuration.ofMinutes(10))
          .expectNext(1)
          .verifyComplete()
      }
    }

    ".dematerialize should dematerialize the underlying mono" in {
      val mono = Mono.just(Signal.next(randomValue))
      StepVerifier.create(mono.dematerialize())
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".doAfterTerminate should call the callback function after the mono is terminated" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.just(randomValue)
        .doAfterTerminate { (v: Long, t: Throwable) =>
          atomicBoolean.compareAndSet(false, true) shouldBe true
          ()
        }
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }

    ".doFinally should call the callback" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.just(randomValue)
        .doFinally(st => atomicBoolean.compareAndSet(false, true) shouldBe true)
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }

    ".doOnCancel should call the callback function when the subscription is cancelled" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.delay(Duration(1, TimeUnit.MINUTES))
        .doOnCancel(() => {
          atomicBoolean.compareAndSet(false, true) shouldBe true
        })

      val subscriptionReference = new AtomicReference[Subscription]()
      mono.subscribe(new BaseSubscriber[Long] {
        override def hookOnSubscribe(subscription: Subscription): Unit = {
          subscriptionReference.set(subscription)
          subscription.request(1)
        }

        override def hookOnNext(value: Long): Unit = ()
      })
      subscriptionReference.get().cancel()
      atomicBoolean shouldBe 'get
    }

    ".doOnNext should call the callback function when the mono emit data successfully" in {
      val atomicLong = new AtomicLong()
      val mono = Mono.just(randomValue)
        .doOnNext(t => atomicLong.compareAndSet(0, t))
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .verifyComplete()
      atomicLong.get() shouldBe randomValue
    }

    ".doOnSuccess should call the callback function when the mono completes successfully" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.empty[Int]
        .doOnSuccess(t => atomicBoolean.compareAndSet(false, true) shouldBe true)
      StepVerifier.create(mono)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }

    ".doOnError" - {
      "with callback function should call the callback function when the mono encounter error" in {
        val atomicBoolean = new AtomicBoolean(false)
        val mono = Mono.error(new RuntimeException())
          .doOnError(t => atomicBoolean.compareAndSet(false, true) shouldBe true)
        StepVerifier.create(mono)
          .expectError(classOf[RuntimeException])
      }
      "with exception type and callback function should call the callback function when the mono encounter exception with the provided type" in {
        val atomicBoolean = new AtomicBoolean(false)
        val mono = Mono.error(new RuntimeException())
          .doOnError(classOf[RuntimeException]: Class[RuntimeException],
            ((t: RuntimeException) => atomicBoolean.compareAndSet(false, true) shouldBe true): SConsumer[RuntimeException])
        StepVerifier.create(mono)
          .expectError(classOf[RuntimeException])
      }
      "with predicate and callback fnction should call the callback function when the predicate returns true" in {
        val atomicBoolean = new AtomicBoolean(false)
        val mono: Mono[Int] = Mono.error[Int](new RuntimeException("Whatever"))
          .doOnError((_: Throwable) => true,
            ((t: Throwable) => atomicBoolean.compareAndSet(false, true) shouldBe true): SConsumer[Throwable])
        StepVerifier.create(mono)
          .expectError(classOf[RuntimeException])

      }
    }

    ".doOnRequest should call the callback function when subscriber request data" in {
      val atomicLong = new AtomicLong(0)
      val mono = Mono.just(randomValue)
        .doOnRequest(l => atomicLong.compareAndSet(0, l))
      mono.subscribe(new BaseSubscriber[Long] {
        override def hookOnSubscribe(subscription: Subscription): Unit = {
          subscription.request(1)
          ()
        }

        override def hookOnNext(value: Long): Unit = ()
      })
      atomicLong.get() shouldBe 1
    }

    ".doOnSubscribe should call the callback function when the mono is subscribed" in {
      val atomicBoolean = new AtomicBoolean(false)
      val mono = Mono.just(randomValue)
        .doOnSubscribe(s => atomicBoolean.compareAndSet(false, true))
      StepVerifier.create(mono)
        .expectNextCount(1)
        .verifyComplete()
      atomicBoolean shouldBe 'get
    }

    ".doOnTerminate should do something on terminate" in {
      val atomicLong = new AtomicLong()
      val mono: Mono[Long] = createMono.doOnTerminate { (l, t) => atomicLong.set(l) }
      StepVerifier.create(mono)
        .expectNext(randomValue)
        .expectComplete()
        .verify()
      atomicLong.get() shouldBe randomValue
    }

    ".elapse" - {
      "should provide the time elapse when this mono emit value" in {
        StepVerifier.withVirtualTime(new Supplier[Mono[(Long, Long)]] {
          override def get(): Mono[(Long, Long)] = Mono.just(randomValue)
            .delaySubscriptionMillis(1000)
            .elapsed()
        }, new Supplier[VirtualTimeScheduler] {
          override def get(): VirtualTimeScheduler = VirtualTimeScheduler.enable(true)
        }, 1)
          .thenAwait(Duration(1, "second"))
          .expectNextMatches(new Predicate[(Long, Long)] {
            override def test(t: (Long, Long)): Boolean = t match {
//                time should be >= 1000 until https://github.com/reactor/reactor-core/issues/351 is fixed
              case (time, data) => time >= 0 && data == randomValue
            }
          })
          .verifyComplete()
      }
      "with TimedScheduler should provide the time elapsed using the provided scheduler when this mono emit value" in {
        val virtualTimeScheduler = VirtualTimeScheduler.create()
        StepVerifier.withVirtualTime(new Supplier[Mono[(Long, Long)]] {
          override def get(): Mono[(Long, Long)] = Mono.just(randomValue)
            .delaySubscriptionMillis(1000)
            .elapsed(virtualTimeScheduler)
        }, new Supplier[VirtualTimeScheduler] {
          override def get(): VirtualTimeScheduler = {
            virtualTimeScheduler
          }
        }, 1)
          .thenAwait(Duration(1, "second"))
          .expectNextMatches(new Predicate[(Long, Long)] {
            override def test(t: (Long, Long)): Boolean = t match {
              case (time, data) => time >= 1000 && data == randomValue
            }
          })
          .verifyComplete()
      }
    }

    ".filter should filter the value of mono where it pass the provided predicate" in {
      val mono = Mono.just(10)
        .filter(i => i < 10)
      StepVerifier.create(mono)
        .verifyComplete()
    }

    ".flatMap" - {
      "with a single mapper should flatmap the value mapped by the provided mapper" in {
        val flux = Mono.just(1).flatMap(i => Flux.just(i, i*2))
        StepVerifier.create(flux)
          .expectNext(1, 2)
          .verifyComplete()
      }
      "with mapperOnNext, mapperOnError and mapperOnComplete should mapped each individual event into values emitted by flux" in {
        val flux = Mono.just(1)
          .flatMap(
            i => Mono.just("one"),
            t => Mono.just("error"),
            () => Mono.just("complete")
          )
        StepVerifier.create(flux)
          .expectNext("one", "complete")
          .verifyComplete()
      }
    }

    ".flatMapIterable should flatmap the value mapped by the provided mapper" in {
      val flux = Mono.just("one").flatMapIterable(str => str.toCharArray)
      StepVerifier.create(flux)
        .expectNext('o','n','e')
        .verifyComplete()
    }

    ".flux should convert this mono into a flux" in {
      val flux = Mono.just(randomValue).flux()
      StepVerifier.create(flux)
        .expectNext(randomValue)
        .verifyComplete()
    }

    ".hasElement should convert to another Mono that emit" - {
      "true if it has element" in {
        val mono = Mono.just(1).hasElement
        StepVerifier.create(mono)
          .expectNext(true)
          .verifyComplete()
      }
      "false if it is empty" in {
        val mono = Mono.empty.hasElement
        StepVerifier.create(mono)
          .expectNext(false)
          .verifyComplete()
      }
    }

    ".handle should handle onNext, onError and onComplete" in {
      val mono = Mono.just(randomValue)
        .handle((l: Long, s: SynchronousSink[String]) => {
          s.next("One")
          s.complete()
        })
      StepVerifier.create(mono)
        .expectNext("One")
        .verifyComplete()
    }

    ".ignoreElement should only emit termination event" in {
      val mono = Mono.just(randomValue).ignoreElement
      StepVerifier.create(mono)
        .verifyComplete()
    }

    "++ should combine this mono and the other" in {
      val mono = just(1) ++ just(2)
      StepVerifier.create(mono)
        .expectNext((1, 2))
        .verifyComplete()
    }

    ".map should map the type of Mono from T to R" in {
      val mono = createMono.map(_.toString)

      StepVerifier.create(mono)
        .expectNext(randomValue.toString)
        .expectComplete()
        .verify()
    }
    ".timeout should raise TimeoutException after duration elapse" in {
      StepVerifier.withVirtualTime(new Supplier[Publisher[Long]] {
        override def get(): Mono[Long] = Mono.delayMillis(10000).timeout(Duration(5, TimeUnit.SECONDS))
      })
        .thenAwait(JDuration.ofSeconds(5))
        .expectError(classOf[TimeoutException])
        .verify()
    }
  }

  private def createMono = {
    Mono.create[Long](monoSink => monoSink.success(randomValue))
  }
}
