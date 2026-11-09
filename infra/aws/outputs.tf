output "api_url" {
  description = "Address clients use to reach the backend."
  value       = "https://${local.api_hostname}"
}

output "instance_id" {
  description = "Host to connect to with SSM Session Manager."
  value       = aws_instance.host.id
}

output "ecr_repository_url" {
  description = "Registry the deploy pipeline pushes backend images to."
  value       = aws_ecr_repository.backend.repository_url
}

output "log_group_name" {
  description = "CloudWatch log group for the host's containers."
  value       = aws_cloudwatch_log_group.containers.name
}
