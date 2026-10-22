# Deploys the Cloud Functions and Firestore rules: make deploy [PROJECT=<projectId>]
# (scripts/deploy-firebase.sh says what it runs.)
.PHONY: deploy
deploy:
	scripts/deploy-firebase.sh $(PROJECT)
