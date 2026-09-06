#!/usr/bin/env python3

# Copyright (c) 2025 Element Creations Ltd.
# Copyright 2024, 2025 New Vector Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

import re
import sys
from xml.dom import minidom

file = sys.argv[1]

# Product names inherited from the upstream application are forbidden in translated values.
forbiddenTerms = {
    r"\b(?:Element|Vector|Riot)\b": [],
}

content = minidom.parse(file)

errors = []

### Strings
for elem in content.getElementsByTagName('string'):
    name = elem.attributes['name'].value
    # Continue if value is empty
    child = elem.firstChild
    if child is None:
        # Should not happen
        continue
    value = child.nodeValue
    # If value contains a forbidden term, add the error to errors
    for (term, exceptions) in forbiddenTerms.items():
        matches = re.search(term, value, re.IGNORECASE)
        if matches and name not in exceptions:
            errors.append('Forbidden term "' + term + '" in string: "' + name + '": ' + value)

### Plurals
for elem in content.getElementsByTagName('plurals'):
    name = elem.attributes['name'].value
    for it in elem.childNodes:
        if it.nodeType != it.ELEMENT_NODE:
            continue
        # Continue if value is empty
        child = it.firstChild
        if child is None:
            # Should not happen
            continue
        value = child.nodeValue
        # If value contains a forbidden term, add the error to errors
        for (term, exceptions) in forbiddenTerms.items():
            matches = re.search(term, value, re.IGNORECASE)
            if matches and name not in exceptions:
                errors.append('Forbidden term "' + term + '" in plural: "' + name + '": ' + value)

# If errors is not empty print the report
if errors:
    print('Error(s) in file ' + file + ":", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    sys.exit(1)
