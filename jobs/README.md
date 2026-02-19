# JOBS Service

The Jobs service is a background job processing system that listens to an SQS queue and processes various asynchronous tasks. Here's what it's currently used for:

Main Job Types:

1. DeletePackageJob & DeleteDatasetJob (DeleteJob.scala)
   - Handles deletion of packages and datasets
   - Deletes data from S3 storage
   - Cleans up time-series data
   - Removes database records
   - Handles cascading deletes (dataset deletion triggers package deletions)
2. StorageCachePopulationJob (StorageCachePopulationJob.scala)
   - Populates storage cache for organizations
   - Calculates and caches storage sizes for datasets and packages
   - Updates organization-level storage totals
   - Can run for specific organizations or all organizations
3. DatasetChangelogEventJob (DatasetChangelogEvent.scala)
   - Logs dataset changelog events
   - Publishes events to SNS topic for downstream processing
   - Tracks various dataset operations (create, update, delete, etc.)
   - Maintains audit trail of dataset changes

Architecture:

- Main Entry Point: Main.scala sets up the job processor and SQS consumer
- Message Processing: ProcessJob.scala routes incoming SQS messages to appropriate job handlers
- Job Queue: Uses AWS SQS (or local SQS for development) to receive job messages
- SNS Integration: Publishes events to SNS topics for further processing
- Database Access: Direct database connections for data operations
- S3 Integration: Handles S3 operations for file storage/deletion

Key Features:

- Asynchronous Processing: Jobs run independently of API requests
- Error Handling: Uses EitherT for functional error handling
- Stream Processing: Uses Akka Streams for efficient processing
- Audit Logging: Integrates with audit system for tracking operations
- Environment Support: Works with both local and AWS environments

The service is essential for:
- Background cleanup operations
- Storage accounting and caching
- Event logging and audit trails
- Resource-intensive operations that shouldn't block API responses
