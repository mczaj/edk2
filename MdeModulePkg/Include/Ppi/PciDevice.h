/** @file

  Copyright (c) 2022, Intel Corporation. All rights reserved.<BR>
  SPDX-License-Identifier: BSD-2-Clause-Patent

**/

#ifndef _EDKII_PCI_DEVICE_PPI_H_
#define _EDKII_PCI_DEVICE_PPI_H_

#include <Protocol/PciIo.h>
#include <Protocol/DevicePath.h>

typedef struct {
  EFI_PCI_IO_PROTOCOL       PciIo;
  EFI_DEVICE_PATH_PROTOCOL  *DevicePath;
} EDKII_PCI_DEVICE_PPI;

#endif