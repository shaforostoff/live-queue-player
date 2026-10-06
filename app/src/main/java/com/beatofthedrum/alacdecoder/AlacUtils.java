/*
** AlacUtils.java
**
** Copyright (c) 2011 Peter McQuillan
**
** All Rights Reserved.
**                       
** Distributed under the BSD Software License (see license.txt)  
**
*/
package com.beatofthedrum.alacdecoder;

public class AlacUtils
{
    public static AlacContext AlacOpenFileInput(String inputfilename)
    {
		java.io.FileInputStream fistream;
		try
		{
			fistream = new java.io.FileInputStream(inputfilename);
		}
		catch (java.io.FileNotFoundException fe)
		{
			AlacContext ac = new AlacContext();
			ac.error_message = "Input file not found";
			ac.error = true;
			return (ac);
		}
		return AlacOpenFileInput(fistream);
	}

	// Not upstream: decodes from an already-open file stream — a file, or a content provider's file
	// descriptor — which must be seekable. Where upstream reopened the file by name to get back to
	// music data that precedes the movie headers, this repositions the stream instead. The stream is
	// closed by AlacCloseFile, also after an error.
	public static AlacContext AlacOpenFileInput(java.io.FileInputStream fistream)
	{
		int headerRead;
		QTMovieT qtmovie = new QTMovieT();
		DemuxResT demux_res = new DemuxResT();
		AlacContext ac = new AlacContext();
		AlacInputStream input_stream;
		AlacFile alac;

		ac.error = false;

		input_stream = new AlacInputStream(fistream);
		ac.file_stream = fistream;
		ac.input_stream = input_stream;

		/* if qtmovie_read returns successfully, the stream is up to
		 * the movie data, which can be used directly by the decoder */
		headerRead = DemuxUtils.qtmovie_read(input_stream, qtmovie, demux_res);

		if (headerRead == 0)
		{
			ac.error = true;
			if (demux_res.format_read == 0)
			{
				ac.error_message = "Failed to load the QuickTime movie headers.";
				if (demux_res.format_read != 0)
					ac.error_message = ac.error_message + " File type: " + DemuxUtils.SplitFourCC(demux_res.format);
			}
			else
			{
				ac.error_message = "Error while loading the QuickTime movie headers.";
			}
			return (ac);
		}
		else if(headerRead == 3)
		{
			/*
			** The music data came before the movie headers. Its position was recorded on the way
			** past (saved_mdat_pos counts bytes from the start of the file); go back there.
			*/
			try
			{
				fistream.getChannel().position(qtmovie.saved_mdat_pos);
			}
			catch (java.io.IOException ioe)
			{
				ac.error_message = "Error when seeking to start of music data";
				ac.error = true;
				return (ac);
			}
			qtmovie.qtstream.currentPos = qtmovie.saved_mdat_pos;
		}

		/* initialise the sound converter */
		
		alac = AlacDecodeUtils.create_alac(demux_res.sample_size, demux_res.num_channels);

		AlacDecodeUtils.alac_set_info(alac, demux_res.codecdata);

		ac.demux_res = demux_res;
		ac.alac = alac;
		try
		{
			ac.data_start = ac.file_stream.getChannel().position();
		}
		catch (java.io.IOException ioe)
		{
			ac.data_start = -1;
		}
		
		return (ac);
			
	}
	
	public static void AlacCloseFile(AlacContext ac)
	{
		if(null != ac.input_stream)
		{
			try
			{
				ac.input_stream.close();
			}
			catch(java.io.IOException ioe)
			{
			}
		}
	}
	
	// Heres where we extract the actual music data
	
	public static int AlacUnpackSamples(AlacContext ac, int[] pDestBuffer)
	{
        int sample_byte_size;
		SampleDuration sampleinfo = new SampleDuration();
        byte[] read_buffer = ac.read_buffer;
		int destBufferSize = 1024 *24 * 3; // 24kb buffer = 4096 frames = 1 alac sample (we support max 24bps)
		int outputBytes;
		MyStream inputStream = new MyStream();

		inputStream.stream = ac.input_stream;
		
		// if current_sample_block is beyond last block then finished
		
		if(ac.current_sample_block >= ac.demux_res.sample_byte_size.length)
		{
			return 0;
		}
		
		if (get_sample_info(ac.demux_res, ac.current_sample_block , sampleinfo) == 0)
		{
			// getting sample failed
				return 0;
		}

        sample_byte_size = sampleinfo.sample_byte_size;

		StreamUtils.stream_read(inputStream, sample_byte_size, read_buffer, 0);
		
		/* now fetch */
		outputBytes = destBufferSize;

		outputBytes = AlacDecodeUtils.decode_frame(ac.alac, read_buffer, pDestBuffer, outputBytes);
		
		ac.current_sample_block = ac.current_sample_block + 1;
		outputBytes -= ac.offset * AlacGetBytesPerSample(ac);
        System.arraycopy(pDestBuffer, ac.offset, pDestBuffer, 0, outputBytes);
        ac.offset = 0;
        return outputBytes;
	
	}
	

	// ---- Random access (not upstream) ----

	// Number of ALAC packets (frames) in the file
	public static int AlacGetNumPackets(AlacContext ac)
	{
		return ac.demux_res.sample_byte_size.length;
	}

	// Samples per channel that a packet decodes to, from the time-to-sample table. A file whose table
	// is missing or short gets the codec's frame length instead, as an estimate.
	public static int AlacGetPacketSamples(AlacContext ac, int packet)
	{
		SampleDuration sampleinfo = new SampleDuration();
		if (get_sample_info(ac.demux_res, packet, sampleinfo) != 0)
		{
			return sampleinfo.sample_duration;
		}
		return ac.alac.setinfo_max_samples_per_frame;
	}

	// Position the decoder so that the next AlacUnpackSamples decodes the given packet. Packets are
	// read back to back from the start of the music data — the same layout sequential decoding
	// relies on — so a packet's offset is the sum of the sizes before it. ALAC packets decode
	// independently, so nothing else needs resetting. Returns false if the file cannot be seeked.
	public static boolean AlacSeekToPacket(AlacContext ac, int packet)
	{
		if (ac.data_start < 0 || packet < 0 || packet >= ac.demux_res.sample_byte_size.length)
		{
			return false;
		}
		if (ac.packet_offsets == null)
		{
			int[] sizes = ac.demux_res.sample_byte_size;
			long[] offsets = new long[sizes.length];
			long pos = ac.data_start;
			for (int i = 0; i < sizes.length; i++)
			{
				offsets[i] = pos;
				pos += sizes[i];
			}
			ac.packet_offsets = offsets;
		}
		try
		{
			ac.file_stream.getChannel().position(ac.packet_offsets[packet]);
		}
		catch (java.io.IOException ioe)
		{
			return false;
		}
		ac.current_sample_block = packet;
		return true;
	}

	// Returns the sample rate of the specified ALAC file

    public static int AlacGetSampleRate(AlacContext ac)
    {
        if ( null != ac && ac.demux_res.sample_rate != 0)
        {
            return ac.demux_res.sample_rate;
        }
        else
        {
            return (44100);
        }
    }
	
	public static int AlacGetNumChannels(AlacContext ac)
    {
        if ( null != ac && ac.demux_res.num_channels != 0)
        {
            return ac.demux_res.num_channels;
        }
        else
        {
            return 2;
        }
    }
	
	public static int AlacGetBitsPerSample(AlacContext ac)
    {
        if (null != ac && ac.demux_res.sample_size != 0)
        {
            return ac.demux_res.sample_size;
        }
        else
        {
            return 16;
        }
    }
	

	public static int AlacGetBytesPerSample(AlacContext ac)
    {
        if ( null != ac && ac.demux_res.sample_size != 0)
        {
            return (int)Math.ceil(ac.demux_res.sample_size/8);
        }
        else
        {
            return 2;
        }
    }
	
	
	// Get total number of samples contained in the Apple Lossless file, or -1 if unknown

    public static int AlacGetNumSamples(AlacContext ac)
    {
		/* calculate output size */
		int num_samples = 0;
		int thissample_duration;
		int thissample_bytesize = 0;
		SampleDuration sampleinfo = new SampleDuration();
		int i;
		boolean error_found = false;
		int retval = 0;
			
		for (i = 0; i < ac.demux_res.sample_byte_size.length; i++)
		{
			thissample_duration = 0;
			thissample_bytesize = 0;

			retval = get_sample_info(ac.demux_res, i, sampleinfo);
			
			if(retval == 0)
			{
				return (-1);
			}
			thissample_duration = sampleinfo.sample_duration;
			thissample_bytesize = sampleinfo.sample_byte_size;

			num_samples += thissample_duration;
		}
		
		return (num_samples);
	}
	

	static int get_sample_info(DemuxResT demux_res, int samplenum, SampleDuration sampleinfo)
	{
		int duration_index_accum = 0;
		int duration_cur_index = 0;

		if (samplenum >= demux_res.sample_byte_size.length)
		{
			System.err.println("sample " + samplenum + " does not exist ");
			return 0;
		}

		if (demux_res.num_time_to_samples == 0)		// was null
		{
			System.err.println("no time to samples");
			return 0;
		}
		while ((demux_res.time_to_sample[duration_cur_index].sample_count + duration_index_accum) <= samplenum)
		{
			duration_index_accum += demux_res.time_to_sample[duration_cur_index].sample_count;
			duration_cur_index++;
			if (duration_cur_index >= demux_res.num_time_to_samples)
			{
				System.err.println("sample " + samplenum + " does not have a duration");
				return 0;
			}
		}

		sampleinfo.sample_duration = demux_res.time_to_sample[duration_cur_index].sample_duration;
		sampleinfo.sample_byte_size = demux_res.sample_byte_size[samplenum];

		return 1;
	}
}
