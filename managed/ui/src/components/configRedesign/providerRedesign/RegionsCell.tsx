/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
import React from 'react';
import { Box } from '@material-ui/core';

import { YBRegion } from './types';

// Region cell for providers
interface RegionsCellProps {
  regions: YBRegion[];
}

export const RegionsCell = ({ regions }: RegionsCellProps) => {
  if (regions.length === 0) {
    return null;
  }

  // TODO: Sort the region list first.
  const sortedRegions = regions.sort((a, b) => (a.code > b.code ? 1 : -1));
  const firstRegion = sortedRegions[0];

  return (
    <Box display="flex">
      {firstRegion.name}
      {sortedRegions.length > 1 && <div>{`+ ${sortedRegions.length - 1}`}</div>}
    </Box>
  );
};
