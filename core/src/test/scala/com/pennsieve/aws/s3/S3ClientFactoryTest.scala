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
}
