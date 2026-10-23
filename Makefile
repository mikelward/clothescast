# Deploys the Cloud Functions and Firestore rules: make deploy [PROJECT=<projectId>]
# (scripts/deploy-firebase.sh says what it runs.)
.PHONY: deploy
deploy:
	scripts/deploy-firebase.sh $(PROJECT)

# Creates the public bucket the alerts ingester writes to, once per project:
# make alerts-bucket PROJECT=<projectId> (scripts/create-alerts-bucket.sh).
.PHONY: alerts-bucket
alerts-bucket:
	scripts/create-alerts-bucket.sh $(PROJECT)
