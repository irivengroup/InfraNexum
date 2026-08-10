package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.core.entitlements.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.*;

/** Atomic independent proof store using a versioned binary format and fsync-before-rename. */
public final class FileIntegrityProofStore implements IndependentIntegrityProofStore {
    private static final int MAGIC=0x494E5854; private static final int VERSION=1;
    private final Path directory;
    public FileIntegrityProofStore(Path directory){this.directory=Objects.requireNonNull(directory,"directory").toAbsolutePath().normalize();}
    @Override public Optional<IntegrityProof> load(InstallationIdentity identity){
        Path path=path(identity); if(!Files.exists(path)) return Optional.empty();
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("unsupported proof format");
            IntegrityProof proof=new IntegrityProof(DomainIdentifier.parse(in.readUTF()),in.readUTF(),Instant.parse(in.readUTF()),Instant.parse(in.readUTF()),in.readLong(),in.readUTF());
            if(in.read()!=-1) throw new IOException("trailing proof data");
            return Optional.of(proof);
        }catch(IOException|RuntimeException e){throw new JdbcPersistenceException("cannot read independent integrity proof",e);}
    }
    @Override public void store(IntegrityProof proof){
        try{Files.createDirectories(directory); secureDirectory(); Path target=path(proof.installationId()); Path tmp=Files.createTempFile(directory,".proof-",".tmp");
            try{secureFile(tmp); try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.WRITE); DataOutputStream out=new DataOutputStream(new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel)))){
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(proof.installationId().toString());out.writeUTF(proof.fingerprint());out.writeUTF(proof.evaluationStartedAt().toString());out.writeUTF(proof.lastReliableAt().toString());out.writeLong(proof.generation());out.writeUTF(proof.mac());out.flush();channel.force(true);
            } Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); secureFile(target); fsyncDirectory();}
            finally{Files.deleteIfExists(tmp);} }
        catch(IOException e){throw new JdbcPersistenceException("cannot persist independent integrity proof",e);}
    }
    @Override public void delete(InstallationIdentity identity){try{Files.deleteIfExists(path(identity));fsyncDirectory();}catch(IOException e){throw new JdbcPersistenceException("cannot delete independent integrity proof",e);}}
    private Path path(InstallationIdentity identity){return path(identity.installationId());}
    private Path path(DomainIdentifier id){return directory.resolve(id+".proof").normalize();}
    private void secureDirectory() throws IOException {try{Files.setPosixFilePermissions(directory,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));}catch(UnsupportedOperationException ignored){}}
    private static void secureFile(Path p)throws IOException{try{Files.setPosixFilePermissions(p,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));}catch(UnsupportedOperationException ignored){}}
    private void fsyncDirectory()throws IOException{try(FileChannel c=FileChannel.open(directory,StandardOpenOption.READ)){c.force(true);}catch(AccessDeniedException|UnsupportedOperationException ignored){}}
}
