resource "aws_sfn_state_machine" "sfn_state_machine" {
  name     = "${var.environment_name}-${var.service_name}-${var.tier}-state-machine-${data.terraform_remote_state.vpc.outputs.aws_region_shortname}"
  role_arn = aws_iam_role.sfn_state_machine_iam_role.arn

  definition = <<EOF
{
  "StartAt": "Initial S3 cleanup",
  "States": {

    "Initial S3 cleanup": {
      "Type": "Task",
      "Resource": "${data.terraform_remote_state.discover_s3clean_lambda.outputs.lambda_function_arn}",
      "Parameters": {
        "publish_bucket.$": "$.publish_bucket",
        "embargo_bucket.$": "$.embargo_bucket",
        "s3_key_prefix.$": "$.s3_publish_key",
        "workflow_id.$": "$.workflow_id"
      },
      "Retry": [
        {
          "ErrorEquals": [
            "States.TaskFailed",
            "Lambda.ServiceException",
            "Lambda.AWSLambdaException",
            "Lambda.SdkClientException"
          ],
          "IntervalSeconds": 5,
          "MaxAttempts": 2,
          "BackoffRate": 2
        }
      ],
      "Catch": [
        {
          "ErrorEquals": [ "States.ALL" ],
          "ResultPath": "$.error",
          "Next": "Notify failure"
        }
      ],
      "ResultPath": null,
      "Next": "Invoke discover-pgdump"
    },

    "Invoke discover-pgdump": {
      "Type": "Task",
      "Resource": "${data.terraform_remote_state.discover_pgdump_lambda.outputs.lambda_function_arn}",
      "Parameters": {
        "organization_schema.$": "$.organization_id",
        "s3_key.$": "$.s3_pgdump_key"
      },
      "Retry": [
        {
          "ErrorEquals": [
            "States.TaskFailed",
            "Lambda.ServiceException",
            "Lambda.AWSLambdaException",
            "Lambda.SdkClientException"
          ],
          "IntervalSeconds": 30,
          "MaxAttempts": 2,
          "BackoffRate": 2
        }
      ],
      "Catch": [
        {
          "ErrorEquals": [ "States.ALL" ],
          "ResultPath": "$.error",
          "Next": "Notify failure"
        }
      ],
      "ResultPath": null,
      "Next": "Run discover-publish:publish-assets"
    },

    "Run discover-publish:publish-assets": {
      "Type": "Task",
      "Resource": "arn:aws:states:::ecs:runTask.sync",
      "Parameters": {
        "LaunchType": "FARGATE",
        "Cluster": "${data.terraform_remote_state.fargate.outputs.ecs_cluster_arn}",
        "TaskDefinition": "${local.discover_publish_task_definition_family}",
        "NetworkConfiguration": {
          "AwsvpcConfiguration": {
            "Subnets": ${jsonencode(data.terraform_remote_state.vpc.outputs.private_subnet_ids)},
            "AssignPublicIp": "DISABLED",
            "SecurityGroups": [ "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_security_group_id}" ]
          }
        },
        "Overrides": {
          "ContainerOverrides": [{
            "Name": "discover-publish",
            "Environment": [{
              "Name": "PUBLISH_ACTION",
              "Value": "publish-assets"
            }, {
              "Name": "DOI",
              "Value.$": "$.doi"
            }, {
              "Name": "ORGANIZATION_ID",
              "Value.$": "$.organization_id"
            }, {
              "Name": "ORGANIZATION_NODE_ID",
              "Value.$": "$.organization_node_id"
            }, {
              "Name": "ORGANIZATION_NAME",
              "Value.$": "$.organization_name"
            }, {
              "Name": "DATASET_ID",
              "Value.$": "$.dataset_id"
            }, {
              "Name": "DATASET_NODE_ID",
              "Value.$": "$.dataset_node_id"
            }, {
              "Name": "PUBLISHED_DATASET_ID",
              "Value.$": "$.published_dataset_id"
            }, {
              "Name": "USER_ID",
              "Value.$": "$.user_id"
            }, {
              "Name": "USER_NODE_ID",
              "Value.$": "$.user_node_id"
            }, {
              "Name": "USER_FIRST_NAME",
              "Value.$": "$.user_first_name"
            }, {
              "Name": "USER_LAST_NAME",
              "Value.$": "$.user_last_name"
            }, {
              "Name": "USER_ORCID",
              "Value.$": "$.user_orcid"
            }, {
              "Name": "S3_PUBLISH_KEY",
              "Value.$": "$.s3_publish_key"
            }, {
              "Name": "S3_BUCKET",
              "Value.$": "$.s3_bucket"
            }, {
              "Name": "CONTRIBUTORS",
              "Value.$": "$.contributors"
            }, {
              "Name": "COLLECTIONS",
              "Value.$": "$.collections"
            }, {
              "Name": "EXTERNAL_PUBLICATIONS",
              "Value.$": "$.external_publications"
            }, {
              "Name": "VERSION",
              "Value.$": "$.version"
            }, {
              "Name": "WORKFLOW_ID",
              "Value.$": "$.workflow_id"
            }]
          }, {
            "Name": "discover-postgres",
            "Environment": [{
              "Name": "S3_PGDUMP_KEY",
              "Value.$": "$.s3_pgdump_key"
            }]
          }]
        }
      },
      "Catch": [
        {
          "ErrorEquals": [ "States.ALL" ],
          "ResultPath": "$.error",
          "Next": "Invoke discover-s3clean"
        }
      ],
      "ResultPath": null,
      "Next": "Run model-publish"
    },

    "Run model-publish": {
      "Type": "Task",
      "Resource": "arn:aws:states:::ecs:runTask.sync",
      "Parameters": {
        "LaunchType": "FARGATE",
        "Cluster": "${data.terraform_remote_state.fargate.outputs.ecs_cluster_arn}",
        "TaskDefinition": "${local.model_publish_task_definition_family}",
        "NetworkConfiguration": {
          "AwsvpcConfiguration": {
            "Subnets": ${jsonencode(data.terraform_remote_state.vpc.outputs.private_subnet_ids)},
            "AssignPublicIp": "DISABLED",
            "SecurityGroups": [ "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_security_group_id}" ]
          }
        },
        "Overrides": {
          "ContainerOverrides": [{
            "Name": "model-publish",
            "Environment": [{
              "Name": "DOI",
              "Value.$": "$.doi"
            }, {
              "Name": "ORGANIZATION_ID",
              "Value.$": "$.organization_id"
            }, {
              "Name": "ORGANIZATION_NODE_ID",
              "Value.$": "$.organization_node_id"
            }, {
              "Name": "ORGANIZATION_NAME",
              "Value.$": "$.organization_name"
            }, {
              "Name": "DATASET_ID",
              "Value.$": "$.dataset_id"
            }, {
              "Name": "DATASET_NODE_ID",
              "Value.$": "$.dataset_node_id"
            }, {
              "Name": "PUBLISHED_DATASET_ID",
              "Value.$": "$.published_dataset_id"
            }, {
              "Name": "USER_ID",
              "Value.$": "$.user_id"
            }, {
              "Name": "USER_NODE_ID",
              "Value.$": "$.user_node_id"
            }, {
              "Name": "USER_FIRST_NAME",
              "Value.$": "$.user_first_name"
            }, {
              "Name": "USER_LAST_NAME",
              "Value.$": "$.user_last_name"
            }, {
              "Name": "USER_ORCID",
              "Value.$": "$.user_orcid"
            }, {
              "Name": "S3_PUBLISH_KEY",
              "Value.$": "$.s3_publish_key"
            }, {
              "Name": "S3_BUCKET",
              "Value.$": "$.s3_bucket"
            }]
          }]
        }
      },
      "Catch": [
        {
          "ErrorEquals": [ "States.ALL" ],
          "ResultPath": "$.error",
          "Next": "Invoke discover-s3clean"
        }
      ],
      "ResultPath": null,
      "Next": "Run discover-publish:finalize"
    },

    "Run discover-publish:finalize": {
      "Type": "Task",
      "Resource": "arn:aws:states:::ecs:runTask.sync",
      "Parameters": {
        "LaunchType": "FARGATE",
        "Cluster": "${data.terraform_remote_state.fargate.outputs.ecs_cluster_arn}",
        "TaskDefinition": "${local.discover_publish_task_definition_family}",
        "NetworkConfiguration": {
          "AwsvpcConfiguration": {
            "Subnets": ${jsonencode(data.terraform_remote_state.vpc.outputs.private_subnet_ids)},
            "AssignPublicIp": "DISABLED",
            "SecurityGroups": [ "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_security_group_id}" ]
          }
        },
        "Overrides": {
          "ContainerOverrides": [{
            "Name": "discover-publish",
            "Environment": [{
              "Name": "PUBLISH_ACTION",
              "Value": "finalize"
            }, {
              "Name": "DOI",
              "Value.$": "$.doi"
            }, {
              "Name": "ORGANIZATION_ID",
              "Value.$": "$.organization_id"
            }, {
              "Name": "ORGANIZATION_NODE_ID",
              "Value.$": "$.organization_node_id"
            }, {
              "Name": "ORGANIZATION_NAME",
              "Value.$": "$.organization_name"
            }, {
              "Name": "DATASET_ID",
              "Value.$": "$.dataset_id"
            }, {
              "Name": "DATASET_NODE_ID",
              "Value.$": "$.dataset_node_id"
            }, {
              "Name": "PUBLISHED_DATASET_ID",
              "Value.$": "$.published_dataset_id"
            }, {
              "Name": "USER_ID",
              "Value.$": "$.user_id"
            }, {
              "Name": "USER_NODE_ID",
              "Value.$": "$.user_node_id"
            }, {
              "Name": "USER_FIRST_NAME",
              "Value.$": "$.user_first_name"
            }, {
              "Name": "USER_LAST_NAME",
              "Value.$": "$.user_last_name"
            }, {
              "Name": "USER_ORCID",
              "Value.$": "$.user_orcid"
            }, {
              "Name": "S3_PUBLISH_KEY",
              "Value.$": "$.s3_publish_key"
            }, {
              "Name": "S3_BUCKET",
              "Value.$": "$.s3_bucket"
            }, {
              "Name": "CONTRIBUTORS",
              "Value.$": "$.contributors"
            }, {
              "Name": "COLLECTIONS",
              "Value.$": "$.collections"
            }, {
              "Name": "EXTERNAL_PUBLICATIONS",
              "Value.$": "$.external_publications"
            }, {
              "Name": "VERSION",
              "Value.$": "$.version"
            }, {
              "Name": "WORKFLOW_ID",
              "Value.$": "$.workflow_id"
            }]
          }, {
            "Name": "discover-postgres",
            "Environment": [{
              "Name": "S3_PGDUMP_KEY",
              "Value.$": "$.s3_pgdump_key"
            }]
          }]
        }
      },
      "Catch": [
        {
          "ErrorEquals": [ "States.ALL" ],
          "ResultPath": "$.error",
          "Next": "Invoke discover-s3clean"
        }
      ],
      "ResultPath": null,
      "Next": "Notify success"
    },

    "Invoke discover-s3clean": {
      "Type": "Task",
      "Resource": "${data.terraform_remote_state.discover_s3clean_lambda.outputs.lambda_function_arn}",
      "Parameters": {
        "publish_bucket.$": "$.publish_bucket",
        "embargo_bucket.$": "$.embargo_bucket",
        "s3_key_prefix.$": "$.s3_publish_key",
        "workflow_id.$": "$.workflow_id"
      },
      "Retry": [
        {
          "ErrorEquals": [
            "States.TaskFailed",
            "Lambda.ServiceException",
            "Lambda.AWSLambdaException",
            "Lambda.SdkClientException"
          ],
          "IntervalSeconds": 5,
          "MaxAttempts": 2,
          "BackoffRate": 2
        }
      ],
      "Catch": [
        {
          "ErrorEquals": [ "States.ALL" ],
          "ResultPath": "$.error",
          "Next": "Notify failure"
        }
      ],
      "ResultPath": null,
      "Next": "Notify failure"
    },

    "Notify success": {
      "Type": "Task",
      "Resource": "arn:aws:states:::sqs:sendMessage",
      "Parameters": {
        "QueueUrl": "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_queue_id}",
        "MessageBody": {
          "organization_id.$": "$.organization_id",
          "dataset_id.$": "$.dataset_id",
          "version.$": "$.version",
          "status": "PUBLISH_SUCCEEDED"
        }
      },
      "End": true
    },

    "Notify failure": {
      "Type": "Task",
      "Resource": "arn:aws:states:::sqs:sendMessage",
      "Parameters": {
        "QueueUrl": "${data.terraform_remote_state.platform_infrastructure.outputs.discover_publish_queue_id}",
        "MessageBody": {
          "organization_id.$": "$.organization_id",
          "dataset_id.$": "$.dataset_id",
          "version.$": "$.version",
          "status": "PUBLISH_FAILED",
          "error.$": "$.error.Cause"
        }
      },
      "End": true
    }
  }
}
EOF
}
