#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>

namespace {
constexpr float maximum=.12f, margin=.025f;
constexpr float infinity=std::numeric_limits<float>::infinity();
constexpr jlong recordSize=7352;
void prefix(const jint* pixels,jint* sums) {
    std::fill(sums,sums+867,0);
    for(int y=0;y<16;y++)for(int x=0;x<16;x++)for(int k=0;k<3;k++) {
        int at=k*289+(y+1)*17+x+1;
        sums[at]=(pixels[y*16+x]>>(16-8*k)&255)+sums[at-1]+sums[at-17]-sums[at-18];
    }
}
int sum(const jint* s,int k,int l,int t,int r,int b) {
    s+=k*289;return s[b*17+r]-s[t*17+r]-s[b*17+l]+s[t*17+l];
}
bool outsideBudget(const jint* reference,const jint* observed,int dx,int dy,double budget) {
    int l=std::max(0,-dx),t=std::max(0,-dy),r=std::min(16,16-dx),b=std::min(16,16-dy),lower=0;
    for(int k=0;k<3;k++)lower+=std::abs(sum(reference,k,l,t,r,b)-sum(observed,k,l+dx,t+dy,r+dx,b+dy));
    if(lower>budget)return true;
    lower=0;
    for(int y=0;y<16;y+=4)for(int x=0;x<16;x+=4) {
        l=std::max(x,-dx);t=std::max(y,-dy);r=std::min(x+4,16-dx);b=std::min(y+4,16-dy);
        if(l>=r || t>=b)continue;
        for(int k=0;k<3;k++)lower+=std::abs(sum(reference,k,l,t,r,b)-sum(observed,k,l+dx,t+dy,r+dx,b+dy));
        if(lower>budget)return true;
    }
    return false;
}
bool pixels(JNIEnv* env,jintArray input,jint* output) {
    if(!input || env->GetArrayLength(input)!=256)return false;
    env->GetIntArrayRegion(input,0,256,output);return !env->ExceptionCheck();
}
int error(jint a,jint b) {
    return std::abs((a>>16&255)-(b>>16&255))+std::abs((a>>8&255)-(b>>8&255))+std::abs((a&255)-(b&255));
}
bool backdrop(jint p) {
    int r=p>>16&255,g=p>>8&255,b=p&255,high=std::max(r,std::max(g,b)),low=std::min(r,std::min(g,b));
    if(high>=128 || high==low)return false;
    float delta=static_cast<float>(high-low);
    float hue=high==r?60*std::fmod((g-b)/delta,6.0f):high==g?60*((b-r)/delta+2):60*((r-g)/delta+4);
    if(hue<0)hue+=360;
    return hue>=170 && hue<=300;
}
// Registration discards only edge strips. Their eligibility depends on the
// observation alone, so compute it once for all compatible family anchors.
uint32_t bodyOffsets(const jboolean* background) {
    int left=0,right=0,top=0,bottom=0;
    for(int depth=0;depth<2;depth++) {
        bool l=true,r=true,t=true,b=true;
        for(int i=0;i<16;i++) {
            l &= background[i*16+depth]!=0;r &= background[i*16+15-depth]!=0;
            t &= background[depth*16+i]!=0;b &= background[(15-depth)*16+i]!=0;
        }
        if(l && left==depth)left++;if(r && right==depth)right++;
        if(t && top==depth)top++;if(b && bottom==depth)bottom++;
    }
    uint32_t offsets=0;
    for(int dy=-bottom;dy<=top;dy++)for(int dx=-right;dx<=left;dx++)
        offsets |= uint32_t{1}<<((dy+2)*5+dx+2);
    return offsets;
}
bool bodyOffsetAllowed(uint32_t offsets,int dx,int dy) {return (offsets & (uint32_t{1}<<((dy+2)*5+dx+2)))!=0;}
float bodyAligned(const jint* observed,const jint* reference,int dx,int dy,const jint* observedSums=nullptr,const jint* referenceSums=nullptr) {
    int supported=(16-std::abs(dx))*(16-std::abs(dy)),total=0;
    double budget=static_cast<double>(std::nextafter(maximum+margin,infinity))*supported*765;
    if(referenceSums && outsideBudget(referenceSums,observedSums,dx,dy,budget))return infinity;
    // Integer addition is exact in any order. Group the original quadrants
    // and reject each independent cover constraint as soon as it is complete.
    // Surviving scores use the same supported pixels and float division.
    for(int qy=0;qy<16;qy+=8)for(int qx=0;qx<16;qx+=8) {
        int l=std::max(qx,-dx),t=std::max(qy,-dy),r=std::min(qx+8,16-dx),b=std::min(qy+8,16-dy);
        int quarter=0,count=(r-l)*(b-t);
        for(int y=t;y<b;y++)for(int x=l;x<r;x++) {
            int delta=error(observed[(y+dy)*16+x+dx],reference[y*16+x]);
            total+=delta;quarter+=delta;if(total>budget)return infinity;
        }
        if(quarter/(count*765.0f)>maximum+margin)return infinity;
    }
    return total/(supported*765.0f);
}
}

// The live callers compare at most one 432x200 HUD or a 16x16 tile. Pin only
// for the bounded memcmp; no allocation, I/O or other work holds these arrays.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_equalPixels(JNIEnv* env,jclass,jintArray first,jintArray second) {
    if(env->IsSameObject(first,second))return JNI_TRUE;
    if(!first || !second)return JNI_FALSE;
    jsize count=env->GetArrayLength(first);
    if(count!=env->GetArrayLength(second))return JNI_FALSE;
    if(count==0)return JNI_TRUE;
    const auto* a=static_cast<const jint*>(env->GetPrimitiveArrayCritical(first,nullptr));
    if(!a)return JNI_FALSE;
    const auto* b=static_cast<const jint*>(env->GetPrimitiveArrayCritical(second,nullptr));
    bool same=b && std::memcmp(a,b,static_cast<size_t>(count)*sizeof(jint))==0;
    if(b)env->ReleasePrimitiveArrayCritical(second,const_cast<jint*>(b),JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(first,const_cast<jint*>(a),JNI_ABORT);
    return same?JNI_TRUE:JNI_FALSE;
}

namespace {
float faceAligned(const jint* observed,const jint* reference,int dx,int dy) {
    int shape=0,spread=0,support=0;
    for(int y=std::max(0,-dy);y<std::min(10,10-dy);y++)
        for(int x=std::max(0,-dx);x<std::min(10,10-dx);x++) {
            for(int k=0;k<3;k++) {
                int a=observed[((y+dy)*10+x+dx)*3+k],b=reference[(y*10+x)*3+k];
                shape+=std::abs(a-b);spread+=std::abs(a)+std::abs(b);
            }
            support++;
        }
    return shape*2>spread?infinity:shape/(support*765.0f);
}
}

// Centered face shape only. Detailed evidence and chroma competition remain in
// Java; these small integer sums and the original division are exact in both paths.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_faceShapeDifference(JNIEnv* env,jclass,jintArray input,jintArray anchor) {
    if(!input || !anchor || env->GetArrayLength(input)!=300 || env->GetArrayLength(anchor)!=300)return infinity;
    jint observed[300],reference[300];
    env->GetIntArrayRegion(input,0,300,observed);env->GetIntArrayRegion(anchor,0,300,reference);
    if(env->ExceptionCheck())return infinity;
    float best=faceAligned(observed,reference,0,0);
    if(best>maximum)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)
        if(dx!=0 || dy!=0)best=std::min(best,faceAligned(observed,reference,dx,dy));
    return best;
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_faceDifferences(JNIEnv* env,jclass,jintArray input,jintArray meanInput,jobject table,jint count) {
    if(!input || !meanInput || env->GetArrayLength(input)!=300 || env->GetArrayLength(meanInput)!=3
            || !table || count<1 || env->GetDirectBufferCapacity(table)!=1216LL*count)return nullptr;
    const auto* references=static_cast<const jint*>(env->GetDirectBufferAddress(table));if(!references)return nullptr;
    jint observed[300],mean[3];env->GetIntArrayRegion(input,0,300,observed);env->GetIntArrayRegion(meanInput,0,3,mean);
    if(env->ExceptionCheck())return nullptr;
    jfloatArray output=env->NewFloatArray(count);if(!output)return nullptr;
    float scores[64];
    for(int offset=0;offset<count;offset+=64) {
        int size=std::min(64,count-offset);
        for(int i=0;i<size;i++) {
            const auto* reference=references+(offset+i)*304;float score=infinity;
            if(reference[0]) {
                int chroma=0;for(int k=0;k<3;k++) {int next=(k+1)%3;chroma+=std::abs((mean[k]-mean[next])-(reference[1+k]-reference[1+next]));}
                float color=chroma/765.0f;
                if(color<=maximum+margin) {
                    score=faceAligned(observed,reference+4,0,0);
                    if(score>maximum)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)
                        if(dx!=0 || dy!=0)score=std::min(score,faceAligned(observed,reference+4,dx,dy));
                    score=std::max(score,color);
                }
            }
            scores[i]=score;
        }
        env->SetFloatArrayRegion(output,offset,size,scores);if(env->ExceptionCheck())return nullptr;
    }
    return output;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_bodyDifference(JNIEnv* env,jclass,jintArray input,jintArray anchor,jbooleanArray backdropMask) {
    jint observed[256],reference[256];jboolean background[256];
    if(!pixels(env,input,observed) || !pixels(env,anchor,reference))return infinity;
    if(backdropMask) {
        if(env->GetArrayLength(backdropMask)!=256)return infinity;
        env->GetBooleanArrayRegion(backdropMask,0,256,background);if(env->ExceptionCheck())return infinity;
    } else for(int i=0;i<256;i++)background[i]=backdrop(observed[i]);
    uint32_t offsets=bodyOffsets(background);
    float best=bodyAligned(observed,reference,0,0);
    // Preserve the Java descriptor's exact zero-offset acceptance behavior.
    if(best<=maximum)return best;
    for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)if((dx!=0 || dy!=0) && bodyOffsetAllowed(offsets,dx,dy))
        best=std::min(best,bodyAligned(observed,reference,dx,dy));
    return best;
}

namespace {
constexpr jlong bodyDescriptorSize=4748;
const jboolean* bodyDescriptor(JNIEnv* env,jobject descriptor) {
    if(!descriptor || env->GetDirectBufferCapacity(descriptor)!=bodyDescriptorSize)return nullptr;
    return static_cast<const jboolean*>(env->GetDirectBufferAddress(descriptor));
}
float preparedBodyScore(const jint* observed,const jint* reference,uint32_t offsets,const jint* observedSums,const jint* referenceSums) {
    float best=bodyAligned(observed,reference,0,0,observedSums,referenceSums);
    if(best<=maximum)return best;
    for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++)if((dx!=0 || dy!=0) && bodyOffsetAllowed(offsets,dx,dy))
        best=std::min(best,bodyAligned(observed,reference,dx,dy,observedSums,referenceSums));
    return best;
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_prepareBodyDescriptor(JNIEnv* env,jclass,jobject descriptor,jintArray input) {
    const auto* bytes=bodyDescriptor(env,descriptor);if(!bytes)return false;
    auto* output=const_cast<jboolean*>(bytes);auto* rgb=reinterpret_cast<jint*>(output);
    if(!pixels(env,input,rgb))return false;
    for(int i=0;i<256;i++)output[1024+i]=backdrop(rgb[i]);
    prefix(rgb,reinterpret_cast<jint*>(output+1280));
    return true;
}
extern "C" JNIEXPORT jfloat JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_preparedBodyDifference(JNIEnv* env,jclass,jobject input,jobject anchor) {
    const auto* observed=bodyDescriptor(env,input);const auto* reference=bodyDescriptor(env,anchor);
    if(!observed || !reference)return infinity;
    return preparedBodyScore(reinterpret_cast<const jint*>(observed),reinterpret_cast<const jint*>(reference),bodyOffsets(observed+1024),
            reinterpret_cast<const jint*>(observed+1280),reinterpret_cast<const jint*>(reference+1280));
}
extern "C" JNIEXPORT jfloat JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_bodyDifferenceAgainstPrepared(JNIEnv* env,jclass,jintArray input,jobject anchor) {
    const auto* reference=bodyDescriptor(env,anchor);jint observed[256],observedSums[867];jboolean background[256];
    if(!reference || !pixels(env,input,observed))return infinity;
    prefix(observed,observedSums);for(int i=0;i<256;i++)background[i]=backdrop(observed[i]);
    return preparedBodyScore(observed,reinterpret_cast<const jint*>(reference),bodyOffsets(background),observedSums,reinterpret_cast<const jint*>(reference+1280));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_bodyMatchesTable(JNIEnv* env,jclass,jintArray input,jobject table,jint count,jint color) {
    if(!table || count<1 || env->GetDirectBufferCapacity(table)!=4752LL*count)return false;
    const auto* references=static_cast<const jboolean*>(env->GetDirectBufferAddress(table));if(!references)return false;
    jint observed[256],observedSums[867];jboolean background[256];
    if(!pixels(env,input,observed))return false;
    prefix(observed,observedSums);for(int i=0;i<256;i++)background[i]=backdrop(observed[i]);
    uint32_t offsets=bodyOffsets(background);
    for(int i=0;i<count;i++) {
        const auto* record=references+4752LL*i;if(*reinterpret_cast<const jint*>(record)!=color)continue;
        const auto* reference=record+4;
        if(preparedBodyScore(observed,reinterpret_cast<const jint*>(reference),offsets,observedSums,reinterpret_cast<const jint*>(reference+1280))<=maximum)return true;
    }
    return false;
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_bodyDifferences(JNIEnv* env,jclass,jintArray input,jobject table,jint count,jbooleanArray enabled) {
    if(!table || !enabled || count<1 || count>24 || env->GetArrayLength(enabled)!=count)return nullptr;
    jlong capacity=env->GetDirectBufferCapacity(table);
    if(capacity<4748LL*count || capacity%4748!=0)return nullptr;
    const auto* references=static_cast<const jboolean*>(env->GetDirectBufferAddress(table));if(!references)return nullptr;
    jint observed[256],observedSums[867];jboolean background[256],compatible[24];
    if(!pixels(env,input,observed))return nullptr;
    env->GetBooleanArrayRegion(enabled,0,count,compatible);if(env->ExceptionCheck())return nullptr;
    prefix(observed,observedSums);for(int i=0;i<256;i++)background[i]=backdrop(observed[i]);
    uint32_t offsets=bodyOffsets(background);
    float scores[24];
    for(int i=0;i<count;i++) {
        const auto* reference=references+4748LL*i;
        scores[i]=compatible[i]?preparedBodyScore(observed,reinterpret_cast<const jint*>(reference),offsets,observedSums,reinterpret_cast<const jint*>(reference+1280)):infinity;
    }
    jfloatArray output=env->NewFloatArray(count);if(!output)return nullptr;
    env->SetFloatArrayRegion(output,0,count,scores);return env->ExceptionCheck()?nullptr:output;
}

namespace {
constexpr int registeredSumsSize=9*51;
// The nine registrations share the same rectangles for every template. Freeze
// reference sums once and compute observation sums once per catalog batch.
void registeredSums(const jint* integral,jint* output,bool observed) {
    int offset=0;
    for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++,offset++) {
        auto* values=output+offset*51;
        int sx=observed?dx:0,sy=observed?dy:0;
        int l=std::max(0,-dx),t=std::max(0,-dy),r=std::min(16,16-dx),b=std::min(16,16-dy);
        for(int k=0;k<3;k++)values[k]=sum(integral,k,l+sx,t+sy,r+sx,b+sy);
        int index=3;
        for(int y=0;y<16;y+=4)for(int x=0;x<16;x+=4) {
            l=std::max(x,-dx);t=std::max(y,-dy);r=std::min(x+4,16-dx);b=std::min(y+4,16-dy);
            for(int k=0;k<3;k++)values[index++]=l<r && t<b?sum(integral,k,l+sx,t+sy,r+sx,b+sy):0;
        }
    }
}
bool outsideRegisteredBudget(const jint* reference,const jint* observed,double budget) {
    int lower=0;
    for(int k=0;k<3;k++)lower+=std::abs(reference[k]-observed[k]);
    if(lower>budget)return true;
    lower=0;
    for(int k=3;k<51;k++) {
        lower+=std::abs(reference[k]-observed[k]);
        if(lower>budget)return true;
    }
    return false;
}
// Each offset's valid pixels are ordered with the positive cover evidence first.
// Rejecting that independent constraint needs no work on the animal underneath.
void coverOrder(const jboolean* cover,jint* supports,jboolean* indices) {
    int offset=0;
    for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++,offset++) {
        int size=0;auto* order=indices+offset*256;
        for(int pass=1;pass>=0;pass--) {
            for(int y=std::max(0,-dy);y<std::min(16,16-dy);y++)for(int x=std::max(0,-dx);x<std::min(16,16-dx);x++)
                if((cover[y*16+x]!=0)==(pass!=0))order[size++]=static_cast<jboolean>(y*16+x);
            if(pass==1)supports[offset]=size;
        }
    }
}
float coverScore(const jint* reference,const jint* observed,float ceiling,const jint* referenceSums,const jint* observedSums,const jint* supports,const jboolean* indices,int positiveWeight=1) {
    float best=infinity;
    int offset=0;
    for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++,offset++) {
        int support=supports[offset],count=(16-std::abs(dx))*(16-std::abs(dy));
        if(support<24)continue;
        double wholeBudget=static_cast<double>(std::nextafter(ceiling,infinity))*count*765;
        double coverBudget=static_cast<double>(std::nextafter(ceiling,infinity))*support*765/positiveWeight;
        // These are the original exact Manhattan lower bounds, with the
        // rectangle sums reused across templates. Positive cover evidence is
        // still a separate mandatory constraint on every surviving offset.
        if(outsideRegisteredBudget(referenceSums+offset*51,observedSums+offset*51,wholeBudget))continue;
        auto* order=indices+offset*256;int shift=dy*16+dx;
        int partial=0;bool rejected=false;
        for(int i=0;i<support;i++) {
            int at=order[i];partial+=error(reference[at],observed[at+shift]);
            if(partial>coverBudget) { rejected=true;break; }
        }
        if(rejected)continue;
        int whole=partial;
        for(int i=support;i<count;i++) {
            int at=order[i];whole+=error(reference[at],observed[at+shift]);
            if(whole>wholeBudget) { rejected=true;break; }
        }
        if(!rejected) {
            float score=std::max(whole/(count*765.0f),positiveWeight*partial/(support*765.0f));
            if(score<=ceiling)best=std::min(best,score);
            if(best==0)return best;
        }
    }
    return best;
}
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_coverDifference(JNIEnv* env,jclass,jintArray anchor,jintArray input,jbooleanArray stencil,jfloat ceiling) {
    jint observed[256],reference[256];jboolean cover[256];
    if(!pixels(env,input,observed) || !pixels(env,anchor,reference) || !stencil || env->GetArrayLength(stencil)!=256)return infinity;
    env->GetBooleanArrayRegion(stencil,0,256,cover);if(env->ExceptionCheck())return infinity;
    jint referenceIntegral[867],observedIntegral[867];prefix(reference,referenceIntegral);prefix(observed,observedIntegral);
    jint referenceSums[registeredSumsSize],observedSums[registeredSumsSize];
    registeredSums(referenceIntegral,referenceSums,false);registeredSums(observedIntegral,observedSums,true);
    jint supports[9];jboolean indices[9*256];coverOrder(cover,supports,indices);
    return coverScore(reference,observed,ceiling,referenceSums,observedSums,supports,indices);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_preparePatternTable(JNIEnv* env,jclass,jobject table,jint count) {
    if(!table || count<1 || env->GetDirectBufferCapacity(table)!=recordSize*count)return false;
    auto* bytes=static_cast<jboolean*>(env->GetDirectBufferAddress(table));if(!bytes)return false;
    for(int i=0;i<count;i++) {
        auto* at=bytes+recordSize*i;auto* header=reinterpret_cast<jint*>(at);
        if((header[0]==1 || header[0]==2) && header[1]==256) {
            jint integral[867];prefix(header+2,integral);
            registeredSums(integral,reinterpret_cast<jint*>(at+1544),false);
            coverOrder(at+1288,reinterpret_cast<jint*>(at+5012),at+5048);
        } else if(header[1]==256)prefix(header+2,reinterpret_cast<jint*>(at+1544));
    }
    return true;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_openkhub_sensefield_Match3NativeMath_patternDifferences(JNIEnv* env,jclass,jintArray input,jobject table,jint count,jfloat ceiling) {
    jint observed[256];
    if(!pixels(env,input,observed) || !table || count<1 || env->GetDirectBufferCapacity(table)!=recordSize*count)return nullptr;
    auto* bytes=static_cast<const jboolean*>(env->GetDirectBufferAddress(table));if(!bytes)return nullptr;
    jfloatArray output=env->NewFloatArray(count);if(!output)return nullptr;
    jint observedSums[867];prefix(observed,observedSums);
    jint observationRegistrations[registeredSumsSize];bool registrationsReady=false;
    // Small chunks keep stack usage fixed even for the largest allowed gallery.
    float scores[64];
    for(int offset=0;offset<count;offset+=64) {
        int size=std::min(64,count-offset);
        for(int i=0;i<size;i++) {
            auto* at=bytes+recordSize*(offset+i);auto* header=reinterpret_cast<const jint*>(at);
            auto* reference=header+2;auto* mask=at+8+1024;
            auto* referenceSums=reinterpret_cast<const jint*>(at+1544);
            if((header[0]==1 || header[0]==2) && header[1]==256) {
                if(!registrationsReady) {registeredSums(observedSums,observationRegistrations,true);registrationsReady=true;}
                scores[i]=coverScore(reference,observed,ceiling,referenceSums,observationRegistrations,reinterpret_cast<const jint*>(at+5012),at+5048,header[0]);
            }
            else if(header[0]==0 && header[1]>=24 && header[1]<=256) {
                int total=0;double budget=static_cast<double>(std::nextafter(ceiling,infinity))*header[1]*765;
                if(header[1]==256 && outsideBudget(referenceSums,observedSums,0,0,budget)) {scores[i]=infinity;continue;}
                for(int p=0;p<256;p++)if(mask[p]) { total+=error(reference[p],observed[p]);if(total>budget)break; }
                scores[i]=total>budget?infinity:total/(header[1]*765.0f);
            } else scores[i]=infinity;
        }
        env->SetFloatArrayRegion(output,offset,size,scores);if(env->ExceptionCheck())return nullptr;
    }
    return output;
}
