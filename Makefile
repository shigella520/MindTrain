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
	$(PYTHON) scripts/validate-skill.py plugins/mindtrain/skills/mindtrain

check-platform:
	mvn test

package-platform:
	mvn -DskipTests package
