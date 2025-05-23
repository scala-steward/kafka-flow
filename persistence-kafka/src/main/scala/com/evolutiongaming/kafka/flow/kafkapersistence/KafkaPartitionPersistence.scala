package com.evolutiongaming.kafka.flow.kafkapersistence

import cats.implicits.*
import cats.{FlatMap, Monad, data}
import com.evolutiongaming.catshelper.{BracketThrowable, Log}
import com.evolutiongaming.kafka.flow.kafka.Codecs.*
import com.evolutiongaming.skafka.*
import com.evolutiongaming.skafka.consumer.AutoOffsetReset.Earliest
import com.evolutiongaming.skafka.consumer.{
  Consumer => SkafkaConsumer,
  ConsumerConfig,
  ConsumerOf,
  ConsumerRecord,
  WithSize
}
import scodec.bits.ByteVector

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

object KafkaPartitionPersistence {

  private case class MissingOffsetError(topicPartition: TopicPartition) extends NoStackTrace

  private[kafkapersistence] def readPartition[F[_]: Monad: Log](
    consumer: SkafkaConsumer[F, String, ByteVector],
    snapshotPartition: TopicPartition,
    targetOffset: Offset
  ): F[BytesByKey] =
    Log[F].info(s"Snapshot topic read started up to offset $targetOffset") *>
      FlatMap[F]
        .tailRecM[BytesByKey, BytesByKey](BytesByKey.empty) { acc =>
          consumer
            .position(snapshotPartition)
            .flatMap {
              case offset if offset >= targetOffset =>
                acc.asRight[BytesByKey].pure[F]
              case _ =>
                consumer
                  .poll(10.millis) // TODO: make poll timeout configurable
                  .map(
                    _.values
                      .values
                      .flatMap(_.toIterable)
                      .foldLeft(acc)(processRecord)
                      .asLeft
                  )
            }
        }

  private[kafkapersistence] def processRecord(
    map: BytesByKey,
    record: ConsumerRecord[String, ByteVector]
  ): BytesByKey = record match {
    case ConsumerRecord(_, _, _, Some(WithSize(key, _)), Some(WithSize(value, _)), _) => map + (key -> value)
    case ConsumerRecord(_, _, _, Some(WithSize(key, _)), None, _)                     => map - key
    case _ => map // ignore records with no key for now
  }

  private[kafkapersistence] def readSnapshots[F[_]: BracketThrowable: Log](
    consumerOf: ConsumerOf[F],
    consumerConfig: ConsumerConfig,
    snapshotTopic: Topic,
    partition: Partition
  )(implicit fromBytes: FromBytes[F, String]): F[BytesByKey] = {
    consumerOf
      .apply[String, ByteVector](
        consumerConfig.copy(
          autoOffsetReset = Earliest,
          common = consumerConfig
            .common
            .copy(clientId = consumerConfig.common.clientId.map(cid => s"$cid-snapshot-$partition"))
        )
      )
      .use { consumer =>
        val snapshotsPartition =
          TopicPartition(topic = snapshotTopic, partition = partition)

        val snapshotPartitionSingleton = data.NonEmptySet.of(snapshotsPartition)
        for {
          _          <- consumer.assign(snapshotPartitionSingleton)
          endOffsets <- consumer.endOffsets(snapshotPartitionSingleton)
          targetOffset <- BracketThrowable[F].fromOption(
            endOffsets.get(snapshotsPartition),
            MissingOffsetError(snapshotsPartition)
          )
          bytesByKey <- readPartition(
            consumer,
            snapshotsPartition,
            targetOffset
          )
          _ <- Log[F].info(
            s"Snapshot topic $snapshotTopic partition $partition read complete at offset $targetOffset, ${bytesByKey.size} keys read"
          )
        } yield bytesByKey
      }
  }
}
