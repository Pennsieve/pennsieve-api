// This would create an alarm if there were no healthy intances of the task running.
#// CREATE TARGET GROUP METRIC ALARM
#resource "aws_cloudwatch_metric_alarm" "target_group_health_cloudwatch_metric_alarm" {
#  alarm_name                = "${var.service_name}-healthy-hosts"
#  comparison_operator       = "LessThanOrEqualToThreshold"
#  evaluation_periods        = "1"
#  metric_name               = "HealthyHostCount"
#  namespace                 = "AWS/ApplicationELB"
#  period                    = "60"
#  statistic                 = "Maximum"
#  threshold                 = "0"
#  alarm_description         = "Check the health of ${var.service_name} target group."
#  insufficient_data_actions = []
#
#  dimensions {
#    TargetGroup  = "${aws_alb_target_group.alb_target_group.arn_suffix}"
#    LoadBalancer = "${data.terraform_remote_state.ecs_cluster.alb_arn_suffix}"
#  }
#}

