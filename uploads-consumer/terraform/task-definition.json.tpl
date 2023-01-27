[
  {
    "cpu": ${uploads_consumer_container_cpu},
    "environment": [
      {
        "name": "ENVIRONMENT",
        "value": "${environment_name}"
      }
    ],
    "image": "${uploads_consumer_image_url}:${image_tag}",
    "memory": ${uploads_consumer_container_memory},
    "name": "uploads-consumer",
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "${cloudwatch_log_group_name}",
        "awslogs-region": "${aws_region}",
        "awslogs-stream-prefix": "${uploads_consumer_stream_prefix}"
      }
    },
    "mountPoints": [${mount_points}],
    "secrets": [
      { "name": "ALERT_TOPIC", "valueFrom": "${sns_alert_topic_arn}" },
      { "name": "ALERT_QUEUE", "valueFrom": "${sqs_deadletter_queue_arn}" },
      { "name": "PENNSIEVE_POSTGRES_DATABASE", "valueFrom": "${pennsieve_postgres_database_arn}" },
      { "name": "PENNSIEVE_POSTGRES_HOST", "valueFrom": "${pennsieve_postgres_host_arn}" },
      { "name": "PENNSIEVE_POSTGRES_PASSWORD", "valueFrom": "${pennsieve_postgres_password_arn}" },
      { "name": "PENNSIEVE_POSTGRES_PORT", "valueFrom": "${pennsieve_postgres_port_arn}" },
      { "name": "PENNSIEVE_POSTGRES_USER", "valueFrom": "${pennsieve_postgres_user_arn}" },
      { "name": "ETL_BUCKET", "valueFrom": "${etl_bucket_arn}" },
      { "name": "JOB_SCHEDULING_SERVICE_HOST", "valueFrom": "${job_scheduling_service_host_arn}" },
      { "name": "UPLOAD_SERVICE_HOST", "valueFrom": "${upload_service_host_arn}" },
      { "name": "JWT_SECRET_KEY", "valueFrom": "${jwt_secret_key_arn}" },
      { "name": "NOTIFICATIONS_SQS_QUEUE", "valueFrom": "${notifications_sqs_queue_arn}" },
      { "name": "SQS_QUEUE", "valueFrom": "${sqs_queue_arn}" },
      { "name": "SQS_QUEUE_V2", "valueFrom": "${sqs_queue_arn}" },
      { "name": "STORAGE_BUCKET", "valueFrom": "${storage_bucket_arn}" },
      { "name": "UPLOADS_BUCKET", "valueFrom": "${uploads_bucket_arn}" }
    ]
  },
  {
    "cpu": ${clamd_container_cpu},
    "image": "${clamd_image_url}:${image_tag}",
    "memoryReservation": ${clamd_container_memory_reservation},
    "memory": ${clamd_container_memory},
    "name": "clamd",
    "portMappings": [
      {
        "containerPort": ${clamd_container_port}
      }
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "${cloudwatch_log_group_name}",
        "awslogs-region": "${aws_region}",
        "awslogs-stream-prefix": "${clamd_stream_prefix}"
      }
    },
    "mountPoints": [${mount_points}]
  }
]
