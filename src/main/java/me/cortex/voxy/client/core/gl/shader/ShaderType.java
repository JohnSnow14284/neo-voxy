package me.cortex.voxy.client.core.gl.shader;

public enum ShaderType {
   VERTEX(35633),
   FRAGMENT(35632),
   COMPUTE(37305),
   MESH(38233),
   TASK(38234);

   public final int gl;

   private ShaderType(int glEnum) {
      this.gl = glEnum;
   }
}
