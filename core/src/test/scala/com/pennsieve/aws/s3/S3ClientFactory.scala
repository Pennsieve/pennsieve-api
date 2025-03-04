package com.pennsieve.aws.s3

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

class S3ClientFactoryTest extends AnyFunSuite{
  test("getRegionFromBucket should return correct region for known bucket suffixes") {
    assert(S3ClientFactory.getRegionFromBucket("pennsieve-storage-afs-1") == "af-south-1")
    assert(S3ClientFactory.getRegionFromBucket("pennsieve-storage-use-1") == "us-east-1")
    assert(S3ClientFactory.getRegionFromBucket("pennsieve-discover-use-2") == "us-east-2")
    assert(S3ClientFactory.getRegionFromBucket("pennsieve-storage-usw-1") == "us-west-1")
    assert(S3ClientFactory.getRegionFromBucket("pennsieve-discover-usw-2") == "us-west-2")
  }

  test("getRegionFromBucket should return us-east-1 for unknown bucket suffixes") {
    assert(S3ClientFactory.getRegionFromBucket("pennsieve-storage-slu-1") == "us-east-1")
  }
}