variable "domain_name" {
  description = "Domain with an existing Route 53 hosted zone, for example example.com."
  type        = string
}

variable "api_subdomain" {
  description = "Subdomain the backend is served from."
  type        = string
  default     = "api"
}

variable "region" {
  description = "AWS region for every resource."
  type        = string
  default     = "us-east-2"
}

variable "github_repository" {
  description = "GitHub repository, as owner/name, whose main branch can deploy."
  type        = string
  default     = "phanmi/tyler_agent"
}

variable "instance_type" {
  description = "EC2 instance type for the single backend host."
  type        = string
  default     = "t3.small"
}

variable "root_volume_gb" {
  description = "Size of the host's root disk, which holds the OS and container images."
  type        = number
  default     = 20
}

variable "data_volume_gb" {
  description = "Size of the disk holding the backend's databases and files."
  type        = number
  default     = 10
}

variable "snapshot_retention_days" {
  description = "Number of daily data volume snapshots to keep."
  type        = number
  default     = 14
}

variable "log_retention_days" {
  description = "Days to keep container logs in CloudWatch."
  type        = number
  default     = 14
}

variable "image_retention_count" {
  description = "Number of backend images to keep in ECR for rollback."
  type        = number
  default     = 10
}
