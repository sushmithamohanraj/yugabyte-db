/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
package com.yugabyte.yw.metrics;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

@Getter
public enum TimeAggregation {
  DEFAULT(StringUtils.EMPTY),
  MIN("min_over_time"),
  MAX("max_over_time"),
  AVG("avg_over_time");

  public static final Set<String> AGGREGATION_FUNCTIONS;

  private final String aggregationFunction;

  TimeAggregation(String aggregationFunction) {
    this.aggregationFunction = aggregationFunction;
  }

  static {
    AGGREGATION_FUNCTIONS =
        Arrays.stream(values())
            .map(TimeAggregation::getAggregationFunction)
            .filter(StringUtils::isNotEmpty)
            .collect(Collectors.toSet());
  }
}
