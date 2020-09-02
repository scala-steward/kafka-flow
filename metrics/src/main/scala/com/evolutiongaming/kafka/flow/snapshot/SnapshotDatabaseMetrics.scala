package com.evolutiongaming.kafka.flow.snapshot

import cats.Monad
import cats.implicits._
import com.evolutiongaming.kafka.flow.KafkaKey
import com.evolutiongaming.kafka.flow.metrics.MetricsOf
import com.evolutiongaming.kafka.flow.metrics.syntax._
import com.evolutiongaming.kafka.journal.ConsRecord
import com.evolutiongaming.smetrics.LabelNames
import com.evolutiongaming.smetrics.MeasureDuration
import com.evolutiongaming.smetrics.MetricsHelper._
import com.evolutiongaming.smetrics.Quantile
import com.evolutiongaming.smetrics.Quantiles

object SnapshotDatabaseMetrics {

  implicit def snapshotDatabaseMetricsOf[F[_]: Monad: MeasureDuration, S]: MetricsOf[F, SnapshotDatabase[F, KafkaKey, S]] = { registry =>
    for {
      persistSummary <- registry.summary(
        name = "snapshot_database_persist_duration_seconds",
        help = "Time required to persist a single snapshot to a database",
        quantiles = Quantiles(Quantile(0.9, 0.05), Quantile(0.99, 0.005)),
        labels = LabelNames("topic", "partition")
      )
      getSummary <- registry.summary(
        name = "snapshot_database_get_duration_seconds",
        help = "Time required to get a single snapshot from a database",
        quantiles = Quantiles(Quantile(0.9, 0.05), Quantile(0.99, 0.005)),
        labels = LabelNames("topic", "partition")
      )
      deleteSummary <- registry.summary(
        name = "snapshot_database_delete_duration_seconds",
        help = "Time required to delete all snapshots for the key from a database",
        quantiles = Quantiles(Quantile(0.9, 0.05), Quantile(0.99, 0.005)),
        labels = LabelNames("topic", "partition")
      )
    } yield database => new SnapshotDatabase[F, KafkaKey, S] {
      def persist(key: KafkaKey, snapshot: S) =
        database.persist(key, snapshot) measureDuration { duration =>
          persistSummary
          .labels(key.topicPartition.topic, key.topicPartition.partition.show)
          .observe(duration.toNanos.nanosToSeconds)
        }
      def get(key: KafkaKey) =
        database.get(key) measureDuration { duration =>
          getSummary
          .labels(key.topicPartition.topic, key.topicPartition.partition.show)
          .observe(duration.toNanos.nanosToSeconds)
        }
      def delete(key: KafkaKey) =
        database.delete(key) measureDuration { duration =>
          deleteSummary
          .labels(key.topicPartition.topic, key.topicPartition.partition.show)
          .observe(duration.toNanos.nanosToSeconds)
        }

    }
  }

}