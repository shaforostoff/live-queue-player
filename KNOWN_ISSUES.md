# Audio preview / multi-output
When several outputs are connected (audio preview available):
- EQ is not available since it breaks audio routing.

## Sony Xperia 10 V / Android 15
Some tracks from the Play Queue are always routed to the secondary ('prelisten') output.
More specifically, opus, flac, alac, aiff are affected, as well as short files in general.
My suspicion is that this happens when Android decides not to use HW acceleration
(either because it doesn't support a given format, or the file is not large enough).

## Samsung A32
Software ALAC decoding workaround doesn't work on this phone



	
