package com.twitter.gizzard
package shards

import com.twitter.conversions.time._
import org.specs.Specification
import org.specs.mock.JMocker
import com.twitter.gizzard.nameserver.LoadBalancer


object ReplicatingShardSpec extends ConfiguredSpecification with JMocker {
  def blackhole[T](n: RoutingNode[T]) = new BlackHoleShard(new ShardInfo("", "", ""), 1, Seq(n))

  "ReplicatingShard" should {
    val shardId = ShardId("fake", "shard")
    val shard1 = mock[fake.Shard]
    val shard2 = mock[fake.Shard]
    val shard3 = mock[fake.Shard]
    val List(node1, node2, node3) = List(shard1, shard2, shard3).zipWithIndex map { case (s, i) =>
      new LeafRoutingNode(s, new ShardInfo("", "shard"+ (i + 1), "fake"), 1)
    }

    val future = new Future("Future!", 1, 1, 1.second, 1.second)
    val shards = List(node1, node2)

    val replicatingShardInfo = new ShardInfo("", "replicating_shard", "hostname")
    var replicatingShard = new ReplicatingShard(replicatingShardInfo, 1, shards, () => shards, Some(future))

    "filters shards" in {
      expect {
        one(shard2).get("name").willReturn(Some("bob"))
      }

      replicatingShard.skip(ShardId("fake", "shard1")).readOperation(_.get("name")) mustEqual Some("bob")
    }

    "read failover" in {
      "when shard1 throws an exception" in {
        val shard1Info = new ShardInfo("", "table_prefix", "hostname")
        val exception = new ShardException("o noes")
        expect {
          one(shard1).get("name").willThrow(exception) then
          one(shard2).get("name").willReturn(Some("bob"))
        }
        replicatingShard.readOperation(_.get("name")) mustEqual Some("bob")
      }

      "when all shards throw an exception" in {
        val shard1Info = new ShardInfo("", "table_prefix", "hostname")
        val exception = new ShardException("o noes")
        expect {
          one(shard1).get("name") willThrow exception
          one(shard2).get("name") willThrow exception
        }
        replicatingShard.readOperation(_.get("name")) must throwA[ShardException]
      }
    }

    "reads happen to shards in order" in {
      expect {
        one(shard1).get("name").willReturn(Some("ted"))
      }
      replicatingShard.readOperation(_.get("name")) mustEqual Some("ted")
    }

    "read all shards" in {
      "when all succeed" in {
        expect {
          one(shard1).get("name") willReturn Some("joe")
          one(shard2).get("name") willReturn Some("bob")
        }

        replicatingShard.readAllOperation(_.get("name")) must haveTheSameElementsAs(List(Right(Some("joe")), Right(Some("bob"))))
      }

      "when one fails" in {
        val ex = new ShardException("hate and pain")

        expect {
          one(shard1).get("name") willThrow ex
          one(shard2).get("name") willReturn Some("bob")
        }

        replicatingShard.readAllOperation(_.get("name")) must haveTheSameElementsAs(List(Left(ex), Right(Some("bob"))))
      }

      "when all fail" in {
        val ex1 = new ShardException("hate")
        val ex2 = new ShardException("bad thoughts")

        expect {
          one(shard1).get("name") willThrow ex1
          one(shard2).get("name") willThrow ex2
        }

        replicatingShard.readAllOperation(_.get("name")) must haveTheSameElementsAs(List(Left(ex1), Left(ex2)))
      }
    }

    "writes happen to all shards" in {
      "in parallel" in {
        "when they succeed" in {
          expect {
            one(shard1).put("name", "alice")
            one(shard2).put("name", "alice")
          }
          replicatingShard.writeOperation(_.put("name", "alice"))
        }

        "when the first one fails" in {
          expect {
            one(shard1).put("name", "alice") willThrow new ShardException("o noes")
            one(shard2).put("name", "alice")
          }
          replicatingShard.writeOperation(_.put("name", "alice")) must throwA[Exception]
        }

        "when one replica is black holed" in {
          expect {
            one(shard2).put("name", "alice")
          }

          val ss = List(blackhole(node1), node2)
          val holed = new ReplicatingShard(replicatingShardInfo, 1, ss, () => ss, Some(future))
          holed.writeOperation(_.put("name", "alice"))
        }

        "when all replicas are black holed" in {
          val ss = shards.map(blackhole)
          val holed = new ReplicatingShard(replicatingShardInfo, 1, ss, () => ss, Some(future))
          holed.writeOperation(_.put("name", "alice")) must throwA[ShardBlackHoleException]
        }
      }

      "in series" in {
        var replicatingShard = new ReplicatingShard(replicatingShardInfo, 1, shards, () => shards, None)

        "normal" in {
          expect {
            one(shard1).put("name", "carol")
            one(shard2).put("name", "carol")
          }
          replicatingShard.writeOperation(_.put("name", "carol"))
        }

        "with an exception" in {
          expect {
            one(shard1).put("name", "carol") willThrow new ShardException("o noes")
            one(shard2).put("name", "carol")
          }
          replicatingShard.writeOperation(_.put("name", "carol")) must throwA[ShardException]
        }

        "when one replica is black holed" in {
          expect {
            one(shard2).put("name", "alice")
          }

          val ss = List(blackhole(node1), node2)
          val holed = new ReplicatingShard(replicatingShardInfo, 1, ss, () => ss, None)
          holed.writeOperation(_.put("name", "alice"))
        }

        "with all black holes" in {
          val ss = shards.map(blackhole)
          val holed = new ReplicatingShard(replicatingShardInfo, 1, ss, () => ss, None)
          holed.writeOperation(_.put("name", "alice")) must throwA[ShardBlackHoleException]
        }
      }
    }

    "rebuildableFailover" in {
      trait EnufShard {
        @throws(classOf[ShardException]) def getPrice: Option[Int]
        @throws(classOf[ShardException]) def setPrice(price: Int)
      }

      val shardInfo = new ShardInfo("fake", "fake", "localhost")
      val mock1 = mock[EnufShard]
      val mock2 = mock[EnufShard]
      val List(node1, node2) = List(mock1, mock2).map(new LeafRoutingNode(_, 1))
      val shards = List(node1, node2)
      val shard = new ReplicatingShard[EnufShard](shardInfo, 1, shards, () => shards, Some(future))

      "first shard has data" in {
        expect {
          one(mock1).getPrice willReturn Some(100)
        }

        shard.rebuildableReadOperation(_.getPrice) { (shard, destShard) => destShard.setPrice(shard.getPrice.get) } mustEqual Some(100)
      }

      "first shard is down, second has data" in {
        expect {
          one(mock1).getPrice willThrow new ShardException("oof!")
          one(mock2).getPrice willReturn Some(100)
        }

        shard.rebuildableReadOperation(_.getPrice) { (shard, destShard) => destShard.setPrice(shard.getPrice.get) } mustEqual Some(100)
      }

      "first shard is empty, second has data" in {
        expect {
          one(mock1).getPrice willReturn None
          exactly(2).of(mock2).getPrice willReturn Some(100)
          one(mock1).setPrice(100)
        }

        shard.rebuildableReadOperation(_.getPrice) { (shard, destShard) => destShard.setPrice(shard.getPrice.get) } mustEqual Some(100)
      }

      "both shards are empty" in {
        expect {
          one(mock1).getPrice willReturn None
          one(mock2).getPrice willReturn None
        }

        shard.rebuildableReadOperation(_.getPrice) { (shard, destShard) => destShard.setPrice(shard.getPrice.get) } mustEqual None
      }

      "both shards are down" in {
        expect {
          one(mock1).getPrice willThrow new ShardException("oof!")
          one(mock2).getPrice willThrow new ShardException("oof!")
        }

        shard.rebuildableReadOperation(_.getPrice) { (shard, destShard) => destShard.setPrice(shard.getPrice.get) } must throwA[ShardOfflineException]
      }
    }
  }
}
