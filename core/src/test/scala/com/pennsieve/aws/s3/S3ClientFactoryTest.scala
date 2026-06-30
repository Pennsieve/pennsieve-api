/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.aws.s3
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.pennsieve.aws.s3.S3ClientFactory

class S3ClientFactoryTest extends AnyFlatSpec with Matchers {

  "S3ClientFactory" should "return the same client for the same region (use1)" in {
    val use1Client1 =
      S3ClientFactory.getClientForBucket("pennsieve-prod-storage-use1")
    val use1Client2 =
      S3ClientFactory.getClientForBucket("pennsieve-prod-logs-use1")

    use1Client1 shouldBe use1Client2
  }

  "S3ClientFactory" should "return the same client for the same region (afs1)" in {
    val afs1Client1 =
      S3ClientFactory.getClientForBucket("pennsieve-prod-storage-afs1")
    val afsClient2 =
      S3ClientFactory.getClientForBucket("pennsieve-prod-logs-afs1")

    afs1Client1 shouldBe afsClient2
  }

  it should "create a different client for a different region" in {
    val afs1Client1 =
      S3ClientFactory.getClientForBucket("pennsieve-prod-storage-afs1")
    val use1Client1 =
      S3ClientFactory.getClientForBucket("pennsieve-prod-storage-use1")

    afs1Client1 should not be use1Client1
  }

  it should "use the default region(us-east-1) if the bucket suffix is not mapped" in {
    val defaultClient = S3ClientFactory.getClientForBucket("unknown-bucket")
    val expectedDefaultClient =
      S3ClientFactory.getClientForBucket("pennsieve-prod-storage-use11")

    defaultClient shouldBe expectedDefaultClient
  }

  it should "return correct region for bucket based on suffix" in {
    S3ClientFactory.getRegionForBucket("my-bucket-use1") shouldBe "us-east-1"
    S3ClientFactory.getRegionForBucket("my-bucket-use2") shouldBe "us-east-2"
    S3ClientFactory.getRegionForBucket("my-bucket-usw1") shouldBe "us-west-1"
    S3ClientFactory.getRegionForBucket("my-bucket-usw2") shouldBe "us-west-2"
    S3ClientFactory.getRegionForBucket("my-bucket-afs1") shouldBe "af-south-1"
    S3ClientFactory.getRegionForBucket("unknown-suffix") shouldBe "us-east-1"
  }

  it should "allow configuring external buckets" in {
    // Configure an external bucket
    S3ClientFactory.configureExternalBuckets(
      Map("test-external-bucket-use1" -> "arn:aws:iam::123456789:role/TestRole")
    )

    // Verify configuration was applied (we can't easily test role assumption without AWS)
    // The test verifies that configureExternalBuckets doesn't throw
    succeed
  }

  it should "allow reconfiguring external buckets" in {
    // Configure initial external buckets
    S3ClientFactory.configureExternalBuckets(
      Map("bucket-a-use1" -> "arn:aws:iam::123456789:role/RoleA")
    )

    // Reconfigure with different buckets
    S3ClientFactory.configureExternalBuckets(
      Map("bucket-b-use1" -> "arn:aws:iam::987654321:role/RoleB")
    )

    // Configuration should succeed without throwing
    succeed
  }

  it should "handle empty external bucket configuration" in {
    S3ClientFactory.configureExternalBuckets(Map.empty)
    succeed
  }
}
