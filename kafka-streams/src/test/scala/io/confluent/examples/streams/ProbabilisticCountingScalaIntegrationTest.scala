/*
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.confluent.examples.streams

import java.util.Properties

import io.confluent.examples.streams.algebird.{CMSStoreSupplier, CMSStore}
import io.confluent.examples.streams.kafka.EmbeddedSingleNodeKafkaCluster
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization._
import org.apache.kafka.streams.kstream.{KStream, KStreamBuilder, Transformer, TransformerSupplier}
import org.apache.kafka.streams.processor.ProcessorContext
import org.apache.kafka.streams.{KafkaStreams, KeyValue, StreamsConfig}
import org.apache.kafka.test.TestUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit._
import org.scalatest.junit.AssertionsForJUnit

/**
  * End-to-end integration test that demonstrates how to probabilistically count items in an input stream.
  *
  * This example uses a custom state store implementation, [[CMSStore]], that is backed by a
  * Count-Min Sketch data structure.
  */
class ProbabilisticCountingScalaIntegrationTest extends AssertionsForJUnit {

  private val privateCluster: EmbeddedSingleNodeKafkaCluster = new EmbeddedSingleNodeKafkaCluster

  @Rule def cluster: EmbeddedSingleNodeKafkaCluster = privateCluster

  private val inputTopic = "inputTopic"
  private val outputTopic = "output-topic"

  @Before
  def startKafkaCluster() {
    cluster.createTopic(inputTopic)
    cluster.createTopic(outputTopic)
  }

  @Test
  def shouldProbabilisticallyCountWords() {
    // To convert between Scala's `Tuple2` and Streams' `KeyValue`.
    import KeyValueImplicits._

    val inputTextLines: Seq[String] = Seq(
      "Hello Kafka Streams",
      "All streams lead to Kafka",
      "Join Kafka Summit"
    )

    val expectedWordCounts: Seq[KeyValue[String, Long]] = Seq(
      ("hello", 1L),
      ("kafka", 1L),
      ("streams", 1L),
      ("all", 1L),
      ("streams", 2L),
      ("lead", 1L),
      ("to", 1L),
      ("kafka", 2L),
      ("join", 1L),
      ("kafka", 3L),
      ("summit", 1L)
    )

    //
    // Step 1: Configure and start the processor topology.
    //
    val streamsConfiguration: Properties = {
      val p = new Properties()
      p.put(StreamsConfig.APPLICATION_ID_CONFIG, "probabilistic-counting-scala-integration-test")
      p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers())
      p.put(StreamsConfig.KEY_SERDE_CLASS_CONFIG, Serdes.ByteArray.getClass.getName)
      p.put(StreamsConfig.VALUE_SERDE_CLASS_CONFIG, Serdes.String.getClass.getName)
      // The commit interval for flushing records to state stores and downstream must be lower than
      // this integration test's timeout (30 secs) to ensure we observe the expected processing results.
      p.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, "10000")
      p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      // Use a temporary directory for storing state, which will be automatically removed after the test.
      p.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory.getAbsolutePath)
      p
    }

    // We want to use `Long` (which refers to `scala.Long`) throughout this code.  However, Kafka
    // ships only with serdes for `java.lang.Long`.  The "trick" below works because there is no
    // `scala.Long` at runtime (in most cases, `scala.Long` is just the primitive `long`), and
    // because Scala converts between `long` and `java.lang.Long` automatically.
    //
    // If you want to see how you work from Scala against Kafka's (Java-based) Streams API without
    // using this trick, take a look at `WordCountScalaIntegrationTest` where we explicitly use
    // `java.lang.Long` (renamed to `JLong`).
    val longSerde: Serde[Long] = Serdes.Long().asInstanceOf[Serde[Long]]

    val builder: KStreamBuilder = new KStreamBuilder()

    // Construct a `KStream` from the input topic, where message values represent lines of text (for
    // the sake of this example, we ignore whatever may be stored in the message keys).
    val textLines: KStream[Array[Byte], String] = builder.stream(inputTopic)

    val cmsStoreName = "cms-store"
    builder.addStateStore(new CMSStoreSupplier[String](cmsStoreName, Serdes.String()))

    class ProbabilisticCounter extends Transformer[Array[Byte], String, KeyValue[String, Long]] {

      private var cmsState: CMSStore[String] = _
      private var processorContext: ProcessorContext = _

      override def init(processorContext: ProcessorContext): Unit = {
        this.processorContext = processorContext
        cmsState = this.processorContext.getStateStore(cmsStoreName).asInstanceOf[CMSStore[String]]
      }

      override def transform(key: Array[Byte], value: String): KeyValue[String, Long] = {
        // Count the record value, think: "+ 1"
        cmsState.put(value)

        // Emit the latest count estimate for the record value
        //
        // Note: Retrieving the count from the CMS (see below) is an expensive operation, which we
        // probably do not want to do on every record (the counting step above is already expensive).
        // One alternative would be to periodically output the latest heavy hitters and their counts.
        KeyValue.pair[String, Long](value, cmsState.get(value))
      }

      override def punctuate(l: Long): KeyValue[String, Long] = null

      override def close(): Unit = {}
    }

    // Scala-Java interoperability: to convert `scala.collection.Iterable` to  `java.util.Iterable`
    // in `flatMapValues()` below.
    import collection.JavaConverters.asJavaIterableConverter

    val approximateWordCounts: KStream[String, Long] = textLines
        .flatMapValues(value => value.toLowerCase.split("\\W+").toIterable.asJava)
        .transform(
          new TransformerSupplier[Array[Byte], String, KeyValue[String, Long]] {
            override def get() = new ProbabilisticCounter
          },
          cmsStoreName)

    approximateWordCounts.to(Serdes.String(), longSerde, outputTopic)

    val streams: KafkaStreams = new KafkaStreams(builder, streamsConfiguration)
    streams.start()

    //
    // Step 2: Publish some input text lines.
    //
    val producerConfig: Properties = {
      val p = new Properties()
      p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers())
      p.put(ProducerConfig.ACKS_CONFIG, "all")
      p.put(ProducerConfig.RETRIES_CONFIG, "0")
      p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer])
      p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer])
      p
    }
    import collection.JavaConverters._
    IntegrationTestUtils.produceValuesSynchronously(inputTopic, inputTextLines.asJava, producerConfig)

    //
    // Step 3: Verify the application's output data.
    //
    val consumerConfig = {
      val p = new Properties()
      p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers())
      p.put(ConsumerConfig.GROUP_ID_CONFIG, "probabilistic-counting-consumer")
      p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer])
      p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[LongDeserializer])
      p
    }
    val actualWordCounts: java.util.List[KeyValue[String, Long]] =
      IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfig, outputTopic, expectedWordCounts.size)
    streams.close()

    // Note: This example only processes a small amount of input data, for which the word counts
    // will actually be exact counts.  However, for large amounts of input data we would expect to
    // observe approximate counts (where the approximate counts would be >= true exact counts).
    assertThat(actualWordCounts).containsExactlyElementsOf(expectedWordCounts.asJava)
  }

}