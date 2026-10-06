/*
** AlacContext.java
**
** Copyright (c) 2011 Peter McQuillan
**
** All Rights Reserved.
**                       
** Distributed under the BSD Software License (see license.txt)  
**
*/

package com.beatofthedrum.alacdecoder;

public class AlacContext
{
	DemuxResT demux_res = new DemuxResT();
	AlacFile alac = new AlacFile();
	AlacInputStream input_stream;
	// Random access (not upstream): input_stream reads this file stream unbuffered, so moving its
	// channel moves the decoder. data_start is where the first packet begins; -1 if unknown.
	java.io.FileInputStream file_stream;
	long data_start = -1;
	long[] packet_offsets; // built on the first seek
	int current_sample_block = 0;
    int offset;
	public boolean error;
	public String error_message = "";
    byte[] read_buffer = new byte[1024 *80]; // sample big enough to hold any input for a single alac frame
}