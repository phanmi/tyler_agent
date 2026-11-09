locals {
  snapshot_tag_key   = "TylerSnapshot"
  snapshot_tag_value = "daily"
}

# Separate from the instance so that replacing the host keeps the data.
resource "aws_ebs_volume" "data" {
  availability_zone = local.availability_zone
  size              = var.data_volume_gb
  type              = "gp3"
  encrypted         = true

  tags = {
    Name                     = "tyler-data"
    (local.snapshot_tag_key) = local.snapshot_tag_value
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.host.id

  # Stop the host first so SQLite is not writing while the disk detaches.
  stop_instance_before_detaching = true
}

data "aws_iam_policy_document" "snapshots_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["dlm.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "snapshots" {
  name               = "tyler-snapshots"
  assume_role_policy = data.aws_iam_policy_document.snapshots_assume.json
}

resource "aws_iam_role_policy_attachment" "snapshots" {
  role       = aws_iam_role.snapshots.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "data" {
  description        = "Daily snapshots of the Tyler data volume"
  execution_role_arn = aws_iam_role.snapshots.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]

    target_tags = {
      (local.snapshot_tag_key) = local.snapshot_tag_value
    }

    schedule {
      name      = "daily"
      copy_tags = true

      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["07:00"]
      }

      retain_rule {
        count = var.snapshot_retention_days
      }
    }
  }
}

resource "aws_ecr_repository" "backend" {
  name                 = "tyler-backend"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep only the images needed for rollback"
      # Tagged only, so the untagged manifests a build attaches do not use up the count.
      selection = {
        tagStatus      = "tagged"
        tagPatternList = ["*"]
        countType      = "imageCountMoreThan"
        countNumber    = var.image_retention_count
      }
      action = {
        type = "expire"
      }
    }]
  })
}

resource "aws_cloudwatch_log_group" "containers" {
  name              = "/tyler/containers"
  retention_in_days = var.log_retention_days
}
