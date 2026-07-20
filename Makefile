BASE_PYTHON ?= /usr/bin/python3
PYTHON := .skill-venv/bin/python
PIP := .skill-venv/bin/pip
VENV_STAMP := .skill-venv/.installed

.PHONY: bootstrap check check-platform package-platform

bootstrap: $(VENV_STAMP)

$(VENV_STAMP): requirements-skill.txt
	$(BASE_PYTHON) -m venv .skill-venv
	$(PIP) install -r requirements-skill.txt
	touch $(VENV_STAMP)

check: $(VENV_STAMP)
	$(PYTHON) -m unittest discover -s tests -v
	$(PYTHON) skill/java-interview-coach/scripts/validate-assets.py .
	$(PYTHON) skill/java-interview-coach/scripts/detect-duplicates.py assets
	$(PYTHON) scripts/validate-skill.py skill/java-interview-coach
	$(PYTHON) scripts/validate-skill.py skills/mindtrain
	$(PYTHON) scripts/validate-skill.py plugins/mindtrain/skills/mindtrain

check-platform:
	mvn test

package-platform:
	mvn -DskipTests package
