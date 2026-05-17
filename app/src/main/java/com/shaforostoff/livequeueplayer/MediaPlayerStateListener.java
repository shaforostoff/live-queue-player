package com.shaforostoff.livequeueplayer;

interface MediaPlayerStateListener {

  void setState(boolean playing);

  /**
   * destroy the music player completely
   */
  void onMediaPlayerDestroy();

  /**
   * reset the music player to accept new audio files
   */
  void onMediaPlayerReset();

}

